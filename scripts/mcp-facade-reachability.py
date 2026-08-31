#!/usr/bin/env python3
"""MCP facade reachability matrix — which roles can be offered which facade tools (#1612).

WHY THIS EXISTS
Issue #1612 was found by querying alpha's live role_permissions and cross-referencing the
seeded mcp_tool_permission groups by hand. That answer had a shelf life of one deploy: a
grant change or an endpoint-guard change silently moves reachability and nothing fails. This
computes the same answer offline, from the two files that decide it, so the matrix can be
regenerated in a build rather than reconstructed from a database.

It reproduces the live alpha figures exactly — permission counts and reachable facade sets
matched for all ten roles the issue scored — which is what makes the offline sources
trustworthy substitutes for the database.

SOURCES (no database, no build)
  A. Facade permission groups — pos-mcp-server db/migration, the per-tool re-derivations
     replayed in order. Each such migration DELETEs a tool's rows before re-inserting, so a
     later one replaces an earlier one rather than adding to it.
  B. Role grants — scripts/fixtures/seed/alpha/security/role-permissions.csv, the bulk-load
     baseline, canonical for role grants since #1613 D8 moved them out of Flyway.

SEMANTICS (V40, #1606 finding 1)
A role reaches a facade iff it holds EVERY code of AT LEAST ONE of that facade's groups —
AND within a group, OR across groups. A group is one @Tool method's required codes.

EventsFacadeTool is excluded from scoring: it is gated on the AUTHENTICATED sentinel, which
is not a role_permissions row but is held by every authenticated caller at runtime.

USAGE
  python3 scripts/mcp-facade-reachability.py                 # print the matrix
  python3 scripts/mcp-facade-reachability.py --json out.json # machine-readable
  python3 scripts/mcp-facade-reachability.py --check         # fail on drift from the baseline
  python3 scripts/mcp-facade-reachability.py --write-baseline

--check compares against scripts/mcp-facade-reachability-baseline.json and fails when any
role gains or loses a facade. Reachability is a product of grants and guards, so it changes
for legitimate reasons — the baseline is a record to update deliberately, not a lock.
"""
import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MIGRATION_DIR = ROOT / "pos-mcp-server/src/main/resources/db/migration"
GRANTS = ROOT / "scripts/fixtures/seed/alpha/security/role-permissions.csv"
BASELINE = ROOT / "scripts/mcp-facade-reachability-baseline.json"

EXCLUDED_TOOLS = {"EventsFacadeTool"}

# A per-tool group re-derivation: VALUES ('group', 'code'), ... WHERE mcp_tool.name = 'Tool'
GROUP_BLOCK = re.compile(
    r"FROM mcp_tool, \(VALUES(.*?)\) AS perms\(grp, code\)\s*WHERE mcp_tool\.name = '([A-Za-z]+)'",
    re.DOTALL,
)
GROUP_PAIR = re.compile(r"\('([^']+)',\s*'([^']+)'\)")


def facade_groups():
    """tool -> group -> {codes}, replaying the group-shaped migrations in version order."""
    out = {}
    migrations = sorted(
        (p for p in MIGRATION_DIR.glob("V*.sql")),
        key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)),
    )
    for migration in migrations:
        for body, tool in GROUP_BLOCK.findall(migration.read_text(encoding="utf-8")):
            if tool in EXCLUDED_TOOLS:
                continue
            groups = out[tool] = {}
            for group, code in GROUP_PAIR.findall(body):
                groups.setdefault(group, set()).add(code)
    if not out:
        sys.exit("no facade groups parsed — the regex, not the seed, is what broke")
    return out


def role_grants():
    """role -> {codes} from the bulk-load baseline."""
    out = {}
    for line in GRANTS.read_text(encoding="utf-8").splitlines()[1:]:
        if not line.strip():
            continue
        role, permissions = line.split(",", 1)
        out[role] = {p for p in permissions.strip().strip('"').split(";") if p}
    if not out:
        sys.exit("no role grants parsed — the baseline file is empty or reshaped")
    return out


def reachability(groups, grants):
    """role -> {'reachable': [tools], 'blocked': {tool: [smallest missing code set]}}"""
    result = {}
    for role, held in grants.items():
        reachable, blocked = [], {}
        for tool in sorted(groups):
            gaps = [codes - held for codes in groups[tool].values()]
            smallest = min(gaps, key=len) if gaps else {"<no group>"}
            if not smallest:
                reachable.append(tool)
            else:
                blocked[tool] = sorted(smallest)
        result[role] = {"reachable": reachable, "blocked": blocked}
    return result


def render(groups, grants, result):
    facades = sorted(groups)
    print(f"facades scored: {len(facades)} (excluded: {', '.join(sorted(EXCLUDED_TOOLS))})\n")
    print(f"{'role':24} {'perms':>5} {'reach':>7}  facades")
    total = 0
    for role in sorted(grants, key=lambda r: len(grants[r])):
        reached = result[role]["reachable"]
        total += len(reached)
        short = ", ".join(t.replace("FacadeTool", "") for t in reached)
        print(f"{role:24} {len(grants[role]):5} {len(reached):>4}/{len(facades):<2}  {short}")
    print(f"\nreachable role-facade pairs: {total}")

    blockers = {}
    for role, data in result.items():
        for tool, gap in data["blocked"].items():
            if len(gap) == 1:
                blockers.setdefault(gap[0], []).append(f"{role}->{tool.replace('FacadeTool', '')}")
    print("\nsingle-code blocks (one permission from reachable):")
    for code, pairs in sorted(blockers.items(), key=lambda kv: -len(kv[1])):
        print(f"  {code:40} {len(pairs):3}  {'; '.join(sorted(pairs))}")

    multi = [
        (role, tool, gap)
        for role, data in result.items()
        for tool, gap in data["blocked"].items()
        if len(gap) > 1
    ]
    print(f"\nblocked by two or more codes: {len(multi)}")
    for role, tool, gap in sorted(multi):
        print(f"  {role} -> {tool.replace('FacadeTool', '')}: {', '.join(gap)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--json", metavar="PATH", help="write the full matrix as JSON")
    parser.add_argument("--check", action="store_true", help="fail if reachability drifted from the baseline")
    parser.add_argument("--write-baseline", action="store_true", help="record current reachability as the baseline")
    args = parser.parse_args()

    groups = facade_groups()
    grants = role_grants()
    result = reachability(groups, grants)
    simple = {role: data["reachable"] for role, data in result.items()}

    if args.write_baseline:
        BASELINE.write_text(json.dumps(simple, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"baseline written: {BASELINE.relative_to(ROOT)}")
        return 0

    if args.check:
        if not BASELINE.exists():
            sys.exit(f"no baseline at {BASELINE.relative_to(ROOT)} — run --write-baseline")
        expected = json.loads(BASELINE.read_text(encoding="utf-8"))
        drift = []
        for role in sorted(set(expected) | set(simple)):
            was, now = set(expected.get(role, [])), set(simple.get(role, []))
            for tool in sorted(now - was):
                drift.append(f"  + {role} gained {tool}")
            for tool in sorted(was - now):
                drift.append(f"  - {role} LOST {tool}")
        if drift:
            print("MCP facade reachability drifted from the baseline:")
            print("\n".join(drift))
            print(
                "\nA grant change or an endpoint-guard change moves this. If the change is intended,\n"
                "re-record it: python3 scripts/mcp-facade-reachability.py --write-baseline"
            )
            return 1
        print(f"OK: reachability matches the baseline ({sum(len(v) for v in simple.values())} role-facade pairs).")
        return 0

    render(groups, grants, result)
    if args.json:
        pathlib.Path(args.json).write_text(json.dumps(result, indent=2, default=list) + "\n", encoding="utf-8")
    return 0


sys.exit(main())
