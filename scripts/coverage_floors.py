#!/usr/bin/env python3
"""Derive, apply, and audit the per-module JaCoCo coverage floors (the ratchet).

Root `pom.xml` gates every module on two properties, defaulted to 0.00:

    <jacoco.line.min>   <jacoco.branch.min>

Each module overrides them a fixed number of points below its measured
coverage (docs/TEST_COVERAGE_IMPROVEMENT_PLAN.md section 6.2). That cushion is
what keeps a parallel `-T 1C` CI run from failing on ordinary run-to-run
variation, measured at roughly 1.7 points on pos-order.

Until this script existed the floors were hand-edited, which made the ratchet
one-directional in name only: nothing raised a floor when a module's coverage
rose, so every point won drifted back out of the gate. Two failure modes follow,
and both are reported here:

  THIN   the floor sits within --min-cushion of measured coverage. Section 6.2:
         "a cushion any thinner than about two points is not a gate, it is a
         coin toss".
  STALE  the floor sits more than --max-cushion below measured coverage, i.e.
         coverage rose and the floor was never raised behind it. The module can
         shed everything it gained without failing a build.

MEASUREMENT CONTRACT. Floors may only be derived from a `-DskipITs` run, because
that is what both binding gates run (section 6.2, "Where the gate actually
runs"). Failsafe inherits the JaCoCo agent through `@{argLine}` and appends to
the same target/jacoco.exec, so an IT-inclusive build silently produces higher
per-module numbers than the gate can ever reproduce. Deriving floors from those
numbers is the exact bug section 6.1 was rewritten to undo: it consumed the
whole cushion and parked modules on the boundary, where run-to-run variation
decided whether the nightly went green. This script therefore refuses to run
when it finds Failsafe reports, unless --allow-its is passed.

Reads each module's OWN target/site/jacoco/jacoco.csv, never the aggregate:
report-aggregate credits a shared library with its consumers' coverage, and it
is not what `jacoco:check` evaluates (section 6.1).

Usage:
  scripts/coverage_floors.py --check     audit only, non-zero exit on drift
  scripts/coverage_floors.py --dry-run   print the floors that would be written
  scripts/coverage_floors.py --apply     rewrite the module poms

The two wrappers -- scripts/check-coverage-floor-drift.sh and
scripts/update-coverage-floors.sh -- call this.
"""

from __future__ import annotations

import argparse
import csv
import math
import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Points below measured coverage that a freshly derived floor sits at. Section
# 6.2 fixes this at 3: enough to absorb a refactor moving a few lines plus the
# ~1.7 points of parallel-CI variation, tight enough to catch a real regression.
DEFAULT_CUSHION = 3.0
# Below this a floor is noise-triggered rather than regression-triggered.
DEFAULT_MIN_CUSHION = 2.0
# Above this the floor was never raised after coverage rose. Two full cushions:
# beyond it, the gap cannot be explained by measurement variance.
DEFAULT_MAX_CUSHION = 6.0
# Modules with fewer lines than this are not worth a floor -- a handful of lines
# swings the ratio by whole points. Section 6.1 leaves exactly this class of
# module unguarded.
DEFAULT_MIN_LINES = 50

LINE_PROP = "jacoco.line.min"
BRANCH_PROP = "jacoco.branch.min"
COUNTERS = ("line", "branch")

OK = "OK"
BREACH = "BREACH"
THIN = "THIN"
STALE = "STALE"
UNGUARDED = "UNGUARDED"
NO_DATA = "NO-DATA"
EXEMPT = "EXEMPT"

# A module's status is the worst of its per-counter findings.
SEVERITY = {BREACH: 5, STALE: 4, THIN: 3, UNGUARDED: 2, NO_DATA: 1, EXEMPT: 0, OK: 0}
FAILING = frozenset({BREACH, THIN, STALE, UNGUARDED})

COMMENT_RE = re.compile(r"measured [0-9.]+% line / [0-9.]+% branch")
CUSHION_RE = re.compile(r"Floors sit [0-9.]+ points under")

COMMENT_LINES = (
    "<!-- Coverage ratchet: measured {line}% line / {branch}% branch by the gate's own",
    "     command, `verify -DskipITs` (docs/TEST_COVERAGE_IMPROVEMENT_PLAN.md §6.1).",
    "     Floors sit {cushion:g} points under. Re-derive them ONLY from a -DskipITs run — an",
    "     IT-inclusive measurement describes coverage the gate cannot see.",
    "     Regenerate with scripts/update-coverage-floors.sh. -->",
)

# Elements that follow <properties> in the POM 4.0.0 sequence. Inserting
# immediately before the first one present keeps the document schema-valid.
AFTER_PROPERTIES = (
    "<dependencyManagement>",
    "<dependencies>",
    "<build>",
    "<reporting>",
    "<profiles>",
    "</project>",
)


def prop_for(counter: str) -> str:
    return LINE_PROP if counter == "line" else BRANCH_PROP


@dataclass
class Counter:
    """A JaCoCo missed/covered pair for one counter of one module."""

    missed: int = 0
    covered: int = 0

    @property
    def total(self) -> int:
        return self.missed + self.covered

    @property
    def ratio(self) -> float | None:
        """Covered ratio, or None when the counter has nothing to measure.

        A module with no branches reports 0/0; JaCoCo skips a limit on an empty
        counter, so there is nothing for a floor to gate either.
        """
        return self.covered / self.total if self.total else None


@dataclass
class Finding:
    counter: str  # "line", "branch", or "-" for a module-wide finding
    status: str
    detail: str


@dataclass
class Module:
    name: str
    pom: Path
    line: Counter | None = None  # None => no jacoco.csv on disk
    branch: Counter | None = None
    floor_line: float | None = None  # None => property absent from the pom
    floor_branch: float | None = None
    has_it_reports: bool = False
    findings: list[Finding] = field(default_factory=list)

    @property
    def status(self) -> str:
        if not self.findings:
            return OK
        return max((f.status for f in self.findings), key=lambda s: SEVERITY[s])

    def floor(self, counter: str) -> float | None:
        return self.floor_line if counter == "line" else self.floor_branch

    def counter(self, counter: str) -> Counter | None:
        return self.line if counter == "line" else self.branch

    def percent(self, counter: str) -> float | None:
        found = self.counter(counter)
        ratio = found.ratio if found else None
        return ratio * 100 if ratio is not None else None


def reactor_modules() -> list[str]:
    """Module names from the root pom's <modules> list, in declaration order."""
    text = (ROOT / "pom.xml").read_text(encoding="utf-8")
    block = re.search(r"<modules>(.*?)</modules>", text, re.DOTALL)
    return re.findall(r"<module>([^<]+)</module>", block.group(1)) if block else []


def read_counters(module_dir: Path) -> tuple[Counter, Counter] | None:
    """Sum LINE_* and BRANCH_* over every class row of a module's jacoco.csv.

    This is the summation section 6.1 prescribes for regenerating its table by
    hand. Returns None when the module was not built with coverage.
    """
    csv_path = module_dir / "target" / "site" / "jacoco" / "jacoco.csv"
    if not csv_path.is_file():
        return None
    line, branch = Counter(), Counter()
    with csv_path.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            line.missed += int(row["LINE_MISSED"])
            line.covered += int(row["LINE_COVERED"])
            branch.missed += int(row["BRANCH_MISSED"])
            branch.covered += int(row["BRANCH_COVERED"])
    return line, branch


def has_it_reports(module_dir: Path) -> bool:
    """True when Failsafe ran here, so jacoco.exec includes IT coverage.

    An absent or empty directory is the `-DskipITs` case -- what the gate
    measures -- so only actual report files mean the numbers are contaminated.
    """
    reports = module_dir / "target" / "failsafe-reports"
    return reports.is_dir() and any(reports.glob("TEST-*.xml"))


def read_floor(pom_text: str, prop: str) -> float | None:
    match = re.search(rf"<{re.escape(prop)}>\s*([^<\s]+)\s*</{re.escape(prop)}>", pom_text)
    return float(match.group(1)) if match else None


def floor_for(ratio: float, cushion: float) -> float:
    """The floor for a measured ratio: cushion points under, rounded down to 2dp.

    Section 6.2. The round() guards floor() against binary-float dust -- 0.80 -
    0.03 lands on 76.99999999999999 often enough to matter, and that would
    silently hand the module an extra point of slack.
    """
    return max(0.0, math.floor(round((ratio - cushion / 100) * 100, 9)) / 100)


def load_modules(names: list[str]) -> list[Module]:
    modules: list[Module] = []
    for name in names:
        module_dir = ROOT / name
        pom = module_dir / "pom.xml"
        if not pom.is_file():
            continue
        pom_text = pom.read_text(encoding="utf-8")
        counters = read_counters(module_dir)
        modules.append(
            Module(
                name=name,
                pom=pom,
                line=counters[0] if counters else None,
                branch=counters[1] if counters else None,
                floor_line=read_floor(pom_text, LINE_PROP),
                floor_branch=read_floor(pom_text, BRANCH_PROP),
                has_it_reports=has_it_reports(module_dir),
            )
        )
    return modules


def evaluate(module: Module, args: argparse.Namespace) -> None:
    """Classify each counter of one module, populating module.findings."""
    module.findings = []

    if module.line is None:
        # Not built, or built without coverage. A warning, never a failure: the
        # PR gate invokes `jacoco:check` directly and writes no report.
        module.findings.append(Finding("-", NO_DATA, "no target/site/jacoco/jacoco.csv"))
        return

    if module.line.total < args.min_lines:
        module.findings.append(
            Finding("-", EXEMPT, f"{module.line.total} lines, under the {args.min_lines}-line threshold")
        )
        return

    for name in COUNTERS:
        measured = module.percent(name)
        floor = module.floor(name)

        if measured is None:
            if floor is not None:
                module.findings.append(
                    Finding(name, NO_DATA, f"floor {floor:.2f} is set but the module has no {name} counters")
                )
            continue

        if floor is None:
            module.findings.append(
                Finding(name, UNGUARDED, f"measured {measured:.1f}%, no {prop_for(name)} in the pom")
            )
            continue

        cushion = measured - floor * 100
        if cushion < 0:
            module.findings.append(
                Finding(name, BREACH, f"measured {measured:.1f}% is below the {floor:.2f} floor")
            )
        elif cushion < args.min_cushion:
            module.findings.append(
                Finding(name, THIN, f"measured {measured:.1f}% is only {cushion:.1f} pts over the {floor:.2f} floor")
            )
        elif cushion > args.max_cushion:
            module.findings.append(
                Finding(name, STALE, f"measured {measured:.1f}% is {cushion:.1f} pts over the {floor:.2f} floor")
            )


def proposed_floors(
    module: Module,
    args: argparse.Namespace,
    dropped: list[str] | None = None,
) -> dict[str, float]:
    """The floors --apply would write, keyed by counter name.

    Ratchet semantics: a proposal below the standing floor is dropped unless
    --allow-lower. Section 6.2: "Never lower a floor without saying why in the
    commit message." Names of refused lowerings are appended to `dropped`, so
    the caller can say that out loud rather than reporting "no changes" for a
    module that is actually under water.
    """
    if module.line is None or module.line.total < args.min_lines:
        return {}

    result: dict[str, float] = {}
    for name in COUNTERS:
        found = module.counter(name)
        if found is None or found.ratio is None:
            continue
        candidate = floor_for(found.ratio, args.cushion)
        if candidate <= 0:
            # A 0.00 floor is not a gate (section 6.2); such a module needs
            # first tests, not a threshold.
            continue
        current = module.floor(name)
        if current is not None:
            if candidate < current and not args.allow_lower:
                if dropped is not None:
                    dropped.append(f"{module.name} {prop_for(name)} {current:.2f} -> {candidate:.2f}")
                continue
            if abs(candidate - current) < 1e-9:
                continue
        result[name] = candidate
    return result


def detect_indent(pom_text: str) -> str:
    """One indentation unit for this pom -- tabs in the root, 4 spaces in modules."""
    return "\t" if re.search(r"(?m)^\t", pom_text) else "    "


def set_property(pom_text: str, prop: str, value: float) -> str | None:
    """Replace an existing property value in place. None when it is absent."""
    pattern = re.compile(rf"(<{re.escape(prop)}>)\s*[^<\s]+\s*(</{re.escape(prop)}>)")
    if not pattern.search(pom_text):
        return None
    return pattern.sub(rf"\g<1>{value:.2f}\g<2>", pom_text, count=1)


def property_lines(values: dict[str, float], indent: str) -> list[str]:
    return [f"{indent}<{prop_for(name)}>{values[name]:.2f}</{prop_for(name)}>" for name in COUNTERS if name in values]


def comment_lines(indent: str, line_pct: float, branch_pct: float, cushion: float) -> list[str]:
    return [
        indent + text.format(line=f"{line_pct:.1f}", branch=f"{branch_pct:.1f}", cushion=cushion)
        for text in COMMENT_LINES
    ]


def insert_properties(
    pom_text: str,
    values: dict[str, float],
    *,
    with_comment: bool,
    line_pct: float,
    branch_pct: float,
    cushion: float,
) -> str:
    """Add floor properties to a pom that lacks them, keeping the POM sequence valid."""
    unit = detect_indent(pom_text)

    existing = re.search(r"(?m)^([ \t]*)<properties>[ \t]*$", pom_text)
    if existing:
        indent = existing.group(1) + unit
        body = comment_lines(indent, line_pct, branch_pct, cushion) if with_comment else []
        body += property_lines(values, indent)
        return pom_text[: existing.end()] + "\n" + "\n".join(body) + pom_text[existing.end() :]

    for anchor in AFTER_PROPERTIES:
        match = re.search(rf"(?m)^([ \t]*){re.escape(anchor)}", pom_text)
        if not match:
            continue
        outer = match.group(1) or unit
        indent = outer + unit
        body = comment_lines(indent, line_pct, branch_pct, cushion) if with_comment else []
        body += property_lines(values, indent)
        block = f"{outer}<properties>\n" + "\n".join(body) + f"\n{outer}</properties>\n\n"
        return pom_text[: match.start()] + block + pom_text[match.start() :]

    raise ValueError("no anchor element found to insert <properties> before")


def refresh_comment(pom_text: str, line_pct: float, branch_pct: float, cushion: float) -> str:
    """Keep the ratchet comment honest after a rewrite.

    Both halves matter: a comment still quoting last quarter's coverage is how a
    reader talks themselves out of re-deriving, and one quoting the wrong cushion
    misdescribes the gate for anyone running a non-default --cushion.
    """
    pom_text = COMMENT_RE.sub(f"measured {line_pct:.1f}% line / {branch_pct:.1f}% branch", pom_text, count=1)
    return CUSHION_RE.sub(f"Floors sit {cushion:g} points under", pom_text, count=1)


def apply_module(module: Module, values: dict[str, float], args: argparse.Namespace) -> bool:
    """Write the proposed floors into a module pom. True when the file changed."""
    if not values:
        return False

    original = module.pom.read_text(encoding="utf-8")
    pom_text = original
    missing: dict[str, float] = {}

    for name, value in values.items():
        updated = set_property(pom_text, prop_for(name), value)
        if updated is None:
            missing[name] = value
        else:
            pom_text = updated

    line_pct = module.percent("line") or 0.0
    branch_pct = module.percent("branch") or 0.0

    if missing:
        pom_text = insert_properties(
            pom_text,
            missing,
            with_comment=COMMENT_RE.search(pom_text) is None,
            line_pct=line_pct,
            branch_pct=branch_pct,
            cushion=args.cushion,
        )

    pom_text = refresh_comment(pom_text, line_pct, branch_pct, args.cushion)

    if pom_text == original:
        return False
    module.pom.write_text(pom_text, encoding="utf-8")
    return True


def annotate(level: str, message: str) -> None:
    """A GitHub Actions annotation in CI, plain text elsewhere."""
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::{level}::{message}")
    else:
        print(f"{level.upper()}: {message}")


def report(modules: list[Module]) -> None:
    header = f"{'module':<30}{'line%':>7}{'floor':>7}{'cush':>7}{'branch%':>9}{'floor':>7}{'cush':>7}  status"
    print(header)
    print("-" * len(header))
    for module in sorted(modules, key=lambda m: (-SEVERITY[m.status], m.name)):
        cells: list[str] = []
        for name in COUNTERS:
            measured = module.percent(name)
            floor = module.floor(name)
            if measured is None:
                cells += ["-", "-", "-"]
                continue
            cells.append(f"{measured:.1f}")
            cells.append(f"{floor:.2f}" if floor is not None else "-")
            cells.append(f"{measured - floor * 100:+.1f}" if floor is not None else "-")
        print(
            f"{module.name:<30}{cells[0]:>7}{cells[1]:>7}{cells[2]:>7}"
            f"{cells[3]:>9}{cells[4]:>7}{cells[5]:>7}  {module.status}"
        )


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Derive, apply, and audit the per-module JaCoCo coverage floors.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="audit only; exit non-zero on drift (CI mode)")
    mode.add_argument("--apply", action="store_true", help="rewrite module poms with the derived floors")
    mode.add_argument("--dry-run", action="store_true", help="print the floors --apply would write (default)")
    parser.add_argument("--cushion", type=float, default=DEFAULT_CUSHION,
                        help=f"points below measured coverage (default {DEFAULT_CUSHION:g})")
    parser.add_argument("--min-cushion", type=float, default=DEFAULT_MIN_CUSHION,
                        help=f"flag a floor closer than this as THIN (default {DEFAULT_MIN_CUSHION:g})")
    parser.add_argument("--max-cushion", type=float, default=DEFAULT_MAX_CUSHION,
                        help=f"flag a floor further than this as STALE (default {DEFAULT_MAX_CUSHION:g})")
    parser.add_argument("--min-lines", type=int, default=DEFAULT_MIN_LINES,
                        help=f"modules smaller than this need no floor (default {DEFAULT_MIN_LINES})")
    parser.add_argument("--allow-lower", action="store_true",
                        help="permit lowering a floor (say why in the commit message)")
    parser.add_argument("--allow-its", action="store_true",
                        help="proceed despite Failsafe reports (the numbers will not match the gate)")
    parser.add_argument("modules", nargs="*", help="limit to these modules (default: every reactor module)")
    args = parser.parse_args(argv)
    if not (args.check or args.apply):
        args.dry_run = True
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])

    modules = load_modules(args.modules or reactor_modules())
    if not modules:
        annotate("error", "no reactor modules found; run from a checkout of the repository")
        return 1

    contaminated = [m.name for m in modules if m.has_it_reports]
    if contaminated and not args.allow_its:
        shown = ", ".join(contaminated[:5]) + (" and others" if len(contaminated) > 5 else "")
        annotate(
            "error",
            f"Failsafe reports found in {shown} -- this coverage includes ITs, which neither binding "
            "gate runs. Re-measure with -DskipITs (docs/TEST_COVERAGE_IMPROVEMENT_PLAN.md §6.1), "
            "or pass --allow-its.",
        )
        return 1

    measured = [m for m in modules if m.line is not None]
    if not measured:
        annotate(
            "error",
            "no module has target/site/jacoco/jacoco.csv; run "
            "`./mvnw verify -DskipITs -Darchunit.skipTests=true` first",
        )
        return 1

    for module in modules:
        evaluate(module, args)

    report(modules)
    print()

    no_data = sorted(m.name for m in modules if m.status == NO_DATA)
    if no_data:
        shown = ", ".join(no_data[:8]) + (f" and {len(no_data) - 8} others" if len(no_data) > 8 else "")
        print(f"{len(no_data)} module(s) had no coverage data and were skipped: {shown}")

    if args.check:
        failures = [m for m in modules if m.status in FAILING]
        if not failures:
            print(
                f"PASS: {len(measured)} measured module(s); every floor sits "
                f"{args.min_cushion:g}-{args.max_cushion:g} pts under its coverage."
            )
            return 0
        print()
        for module in sorted(failures, key=lambda m: (-SEVERITY[m.status], m.name)):
            for finding in module.findings:
                if finding.status in FAILING:
                    annotate("error", f"{module.name} {finding.counter}: {finding.status} -- {finding.detail}")
        print()
        print("Re-derive the floors from a -DskipITs build and commit the result:")
        print("  ./mvnw -pl pos-coverage-aggregate -am verify -DskipITs -Darchunit.skipTests=true -T 1C")
        print("  scripts/update-coverage-floors.sh --apply")
        return 1

    by_name = {m.name: m for m in modules}
    dropped: list[str] = []
    changes = {m.name: proposed_floors(m, args, dropped) for m in modules}
    changes = {name: values for name, values in changes.items() if values}

    if dropped:
        print(f"{len(dropped)} floor(s) would drop and were left alone (pass --allow-lower, and say why in the commit):")
        for entry in dropped:
            print(f"  {entry}")
        print()

    if not changes:
        print("No floor changes: every module's floor already matches its measured coverage.")
        return 0

    print(f"{'module':<30}{'property':<22}{'current':>9}{'proposed':>10}")
    print("-" * 71)
    for name, values in changes.items():
        module = by_name[name]
        for counter, value in values.items():
            current = module.floor(counter)
            shown = f"{current:.2f}" if current is not None else "-"
            print(f"{name:<30}{prop_for(counter):<22}{shown:>9}{value:>10.2f}")
    print()

    if args.dry_run:
        print(f"{len(changes)} module(s) would change. Re-run with --apply to write them.")
        return 0

    written = [name for name, values in changes.items() if apply_module(by_name[name], values, args)]
    print(f"Updated {len(written)} module pom(s): {', '.join(sorted(written))}")
    print("Review the diff and commit; a lowered floor needs its reason in the commit message.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
