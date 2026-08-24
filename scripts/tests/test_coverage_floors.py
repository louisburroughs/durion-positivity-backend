"""Tests for scripts/coverage_floors.py -- the coverage-floor ratchet."""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import coverage_floors as cf  # noqa: E402

CSV_HEADER = (
    "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,"
    "LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED"
)

# Every row of the authoritative baseline table in
# docs/TEST_COVERAGE_IMPROVEMENT_PLAN.md §6.1: measured line%, measured branch%,
# and the floors that table publishes. The floors are the specification; this
# asserts the script's arithmetic reproduces them exactly rather than
# approximately, because a floor one hundredth too high is a false red every
# night and one too low is slack nobody notices.
BASELINE_6_1 = [
    ("pos-accounting", 80.3, 67.3, 0.77, 0.64),
    ("pos-inventory", 79.2, 64.1, 0.76, 0.61),
    ("pos-workorder", 77.7, 63.1, 0.74, 0.60),
    ("pos-mcp-server", 80.2, 68.5, 0.77, 0.65),
    ("pos-customer", 82.4, 69.6, 0.79, 0.66),
    ("pos-supplier", 87.3, 73.5, 0.84, 0.70),
    ("pos-order", 84.4, 71.3, 0.81, 0.68),
    ("pos-security-service", 77.1, 66.6, 0.74, 0.63),
    ("pos-invoice", 77.3, 63.7, 0.74, 0.60),
    ("pos-catalog", 77.6, 61.6, 0.74, 0.58),
    ("pos-people", 78.7, 73.1, 0.75, 0.70),
    ("pos-warranty", 87.3, 78.9, 0.84, 0.75),
    ("pos-location", 78.1, 66.1, 0.75, 0.63),
    ("pos-shop-manager", 79.9, 63.5, 0.76, 0.60),
    ("pos-bulk-loader", 76.6, 65.3, 0.73, 0.62),
    ("pos-people-contact", 72.8, 62.2, 0.69, 0.59),
    ("pos-marketing", 90.7, 85.2, 0.87, 0.82),
    ("pos-price", 94.4, 80.4, 0.91, 0.77),
    ("pos-vehicle-inventory", 79.6, 74.7, 0.76, 0.71),
    ("pos-tax", 78.5, 66.8, 0.75, 0.63),
    ("pos-domain-events", 84.9, 78.9, 0.81, 0.75),
    ("pos-vehicle-fitment", 78.4, 63.6, 0.75, 0.60),
    ("pos-event-receiver", 77.6, 87.2, 0.74, 0.84),
    ("pos-api-gateway", 85.6, 72.2, 0.82, 0.69),
    ("pos-security-common", 79.4, 80.7, 0.76, 0.77),
    ("pos-documents", 72.9, 75.0, 0.69, 0.72),
    ("pos-openapi-validation", 94.2, 81.2, 0.91, 0.78),
    ("pos-vehicle-reference-nhtsa", 53.4, 58.3, 0.50, 0.55),
    ("pos-events", 59.8, 57.1, 0.56, 0.54),
    ("pos-document-helper", 95.7, 90.0, 0.92, 0.87),
    ("pos-tax-common", 89.4, 74.1, 0.86, 0.71),
    ("pos-vehicle-reference-carapi", 78.3, 88.9, 0.75, 0.85),
    ("pos-image", 31.1, 100.0, 0.28, 0.97),
]


def options(**overrides) -> argparse.Namespace:
    defaults = dict(
        cushion=cf.DEFAULT_CUSHION,
        min_cushion=cf.DEFAULT_MIN_CUSHION,
        max_cushion=cf.DEFAULT_MAX_CUSHION,
        min_lines=cf.DEFAULT_MIN_LINES,
        allow_lower=False,
        allow_its=False,
    )
    defaults.update(overrides)
    return argparse.Namespace(**defaults)


def module(line=(20, 80), branch=(35, 65), floor_line=0.77, floor_branch=0.62, pom=Path("pom.xml")) -> cf.Module:
    return cf.Module(
        name="pos-example",
        pom=pom,
        line=cf.Counter(*line) if line else None,
        branch=cf.Counter(*branch) if branch else None,
        floor_line=floor_line,
        floor_branch=floor_branch,
    )


def write_csv(module_dir: Path, rows: list[tuple[int, int, int, int]]) -> None:
    """rows: (branch_missed, branch_covered, line_missed, line_covered)."""
    report = module_dir / "target" / "site" / "jacoco"
    report.mkdir(parents=True, exist_ok=True)
    lines = [CSV_HEADER]
    for index, (bm, bc, lm, lc) in enumerate(rows):
        lines.append(f"pos-example,com.positivity.example,Class{index},0,0,{bm},{bc},{lm},{lc},0,0,0,0")
    (report / "jacoco.csv").write_text("\n".join(lines) + "\n", encoding="utf-8")


class TestFloorFor:
    @pytest.mark.parametrize("name,line_pct,branch_pct,line_floor,branch_floor", BASELINE_6_1)
    def test_reproduces_the_published_baseline(self, name, line_pct, branch_pct, line_floor, branch_floor):
        assert cf.floor_for(line_pct / 100, 3.0) == pytest.approx(line_floor), name
        assert cf.floor_for(branch_pct / 100, 3.0) == pytest.approx(branch_floor), name

    def test_rounds_down_never_up(self):
        # 0.809 - 0.03 = 0.779; rounding up would hand the gate a floor the
        # module does not actually clear.
        assert cf.floor_for(0.809, 3.0) == pytest.approx(0.77)

    def test_survives_binary_float_dust(self):
        # 0.80 - 0.03 evaluates to 0.7700000000000001 in IEEE 754; a naive
        # floor() on a value that landed just under would give away a point.
        assert cf.floor_for(0.80, 3.0) == pytest.approx(0.77)
        assert cf.floor_for(0.50, 3.0) == pytest.approx(0.47)
        assert cf.floor_for(1.00, 3.0) == pytest.approx(0.97)

    def test_never_negative(self):
        assert cf.floor_for(0.01, 3.0) == 0.0

    def test_cushion_is_configurable(self):
        assert cf.floor_for(0.873, 5.0) == pytest.approx(0.82)


class TestCounter:
    def test_ratio_of_empty_counter_is_none(self):
        assert cf.Counter(0, 0).ratio is None

    def test_ratio(self):
        assert cf.Counter(missed=20, covered=80).ratio == pytest.approx(0.80)


class TestReadCounters:
    def test_sums_every_class_row(self, tmp_path):
        write_csv(tmp_path, [(5, 15, 10, 90), (5, 5, 10, 10)])
        line, branch = cf.read_counters(tmp_path)
        assert (line.missed, line.covered) == (20, 100)
        assert (branch.missed, branch.covered) == (10, 20)

    def test_missing_report_is_none(self, tmp_path):
        assert cf.read_counters(tmp_path) is None


class TestHasItReports:
    def test_detects_failsafe_output(self, tmp_path):
        reports = tmp_path / "target" / "failsafe-reports"
        reports.mkdir(parents=True)
        (reports / "TEST-com.positivity.example.ThingIT.xml").write_text("<testsuite/>", encoding="utf-8")
        assert cf.has_it_reports(tmp_path) is True

    def test_empty_directory_is_the_skipits_case(self, tmp_path):
        (tmp_path / "target" / "failsafe-reports").mkdir(parents=True)
        assert cf.has_it_reports(tmp_path) is False

    def test_absent_directory(self, tmp_path):
        assert cf.has_it_reports(tmp_path) is False


class TestEvaluate:
    def test_healthy_cushion_is_ok(self):
        target = module(line=(20, 80), floor_line=0.77, branch=(35, 65), floor_branch=0.62)
        cf.evaluate(target, options())
        assert target.status == cf.OK

    def test_thin_cushion(self):
        # pos-supplier's real state when this script was written: 1.0 pt over.
        target = module(line=(15, 85), floor_line=0.84, branch=(29, 71), floor_branch=0.70)
        cf.evaluate(target, options())
        assert target.status == cf.THIN

    def test_stale_floor(self):
        target = module(line=(9, 91), floor_line=0.81, branch=(18, 82), floor_branch=0.75)
        cf.evaluate(target, options())
        assert target.status == cf.STALE

    def test_breach(self):
        target = module(line=(30, 70), floor_line=0.77)
        cf.evaluate(target, options())
        assert target.status == cf.BREACH

    def test_unguarded_module_with_real_code(self):
        target = module(floor_line=None, floor_branch=None)
        cf.evaluate(target, options())
        assert target.status == cf.UNGUARDED

    def test_small_module_is_exempt(self):
        target = module(line=(10, 20), floor_line=None, floor_branch=None)
        cf.evaluate(target, options())
        assert target.status == cf.EXEMPT

    def test_module_without_coverage_data(self):
        target = module(line=None, branch=None)
        cf.evaluate(target, options())
        assert target.status == cf.NO_DATA

    def test_branchless_module_is_not_penalised(self):
        target = module(branch=(0, 0), floor_branch=None)
        cf.evaluate(target, options())
        assert target.status == cf.OK

    def test_branch_floor_set_on_a_branchless_module_is_reported(self):
        target = module(branch=(0, 0), floor_branch=0.60)
        cf.evaluate(target, options())
        assert target.status == cf.NO_DATA

    def test_status_is_the_worst_finding(self):
        target = module(line=(9, 91), floor_line=0.81, branch=(50, 50), floor_branch=0.62)
        cf.evaluate(target, options())
        assert target.status == cf.BREACH


class TestProposedFloors:
    def test_raises_a_stale_floor(self):
        target = module(line=(9, 91), floor_line=0.81, branch=(18, 82), floor_branch=0.75)
        assert cf.proposed_floors(target, options()) == {"line": 0.88, "branch": 0.79}

    def test_refuses_to_lower_by_default(self):
        target = module(line=(30, 70), floor_line=0.77, branch=(50, 50), floor_branch=0.62)
        assert cf.proposed_floors(target, options()) == {}

    def test_records_refused_lowerings(self):
        target = module(line=(30, 70), floor_line=0.77, branch=(50, 50), floor_branch=0.62)
        dropped: list[str] = []
        assert cf.proposed_floors(target, options(), dropped) == {}
        assert dropped == [
            "pos-example jacoco.line.min 0.77 -> 0.67",
            "pos-example jacoco.branch.min 0.62 -> 0.47",
        ]

    def test_lowers_only_when_asked(self):
        target = module(line=(30, 70), floor_line=0.77, branch=(50, 50), floor_branch=0.62)
        assert cf.proposed_floors(target, options(allow_lower=True)) == {"line": 0.67, "branch": 0.47}

    def test_no_change_when_already_correct(self):
        target = module(line=(20, 80), floor_line=0.77, branch=(35, 65), floor_branch=0.62)
        assert cf.proposed_floors(target, options()) == {}

    def test_proposes_a_floor_for_an_unguarded_module(self):
        target = module(floor_line=None, floor_branch=None)
        assert cf.proposed_floors(target, options()) == {"line": 0.77, "branch": 0.62}

    def test_never_proposes_a_zero_floor(self):
        # "A 0.00 floor is not a gate" (§6.2) -- an all-zero module needs first
        # tests, not a threshold.
        target = module(line=(100, 0), branch=(100, 0), floor_line=None, floor_branch=None)
        assert cf.proposed_floors(target, options()) == {}

    def test_small_module_gets_nothing(self):
        target = module(line=(10, 20), floor_line=None, floor_branch=None)
        assert cf.proposed_floors(target, options()) == {}


POM_WITH_FLOORS = """<?xml version="1.0" encoding="UTF-8"?>
<project>
    <artifactId>pos-example</artifactId>

    <properties>
        <!-- Coverage ratchet: measured 79.2% line / 64.1% branch by the gate's own
             command, `verify -DskipITs` (docs/TEST_COVERAGE_IMPROVEMENT_PLAN.md §6.1).
             Floors sit 3 points under. Re-derive them ONLY from a -DskipITs run — an
             IT-inclusive measurement describes coverage the gate cannot see. -->
        <jacoco.line.min>0.76</jacoco.line.min>
        <jacoco.branch.min>0.61</jacoco.branch.min>
    </properties>

    <dependencies>
    </dependencies>
</project>
"""

POM_WITHOUT_PROPERTIES = """<?xml version="1.0" encoding="UTF-8"?>
<project>
    <artifactId>pos-web-common</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>com.positivity</groupId>
            <artifactId>pos-shared-dtos</artifactId>
        </dependency>
    </dependencies>
</project>
"""

POM_WITH_EMPTY_PROPERTIES = """<?xml version="1.0" encoding="UTF-8"?>
<project>
    <artifactId>pos-example</artifactId>

    <properties>
        <java.version>25</java.version>
    </properties>

    <build>
    </build>
</project>
"""


class TestPomEditing:
    def test_set_property_replaces_in_place(self):
        result = cf.set_property(POM_WITH_FLOORS, cf.LINE_PROP, 0.81)
        assert "<jacoco.line.min>0.81</jacoco.line.min>" in result
        assert "<jacoco.branch.min>0.61</jacoco.branch.min>" in result

    def test_set_property_is_none_when_absent(self):
        assert cf.set_property(POM_WITHOUT_PROPERTIES, cf.LINE_PROP, 0.81) is None

    def test_set_property_always_two_decimals(self):
        result = cf.set_property(POM_WITH_FLOORS, cf.LINE_PROP, 0.8)
        assert "<jacoco.line.min>0.80</jacoco.line.min>" in result

    def test_insert_into_existing_properties(self):
        result = cf.insert_properties(
            POM_WITH_EMPTY_PROPERTIES,
            {"line": 0.75, "branch": 0.63},
            with_comment=True,
            line_pct=78.1,
            branch_pct=66.1,
            cushion=3.0,
        )
        assert "        <jacoco.line.min>0.75</jacoco.line.min>" in result
        assert "        <jacoco.branch.min>0.63</jacoco.branch.min>" in result
        assert "measured 78.1% line / 66.1% branch" in result
        assert result.count("<properties>") == 1
        assert "<java.version>25</java.version>" in result

    def test_insert_creates_properties_before_dependencies(self):
        result = cf.insert_properties(
            POM_WITHOUT_PROPERTIES,
            {"line": 0.82, "branch": 0.62},
            with_comment=True,
            line_pct=85.6,
            branch_pct=65.3,
            cushion=3.0,
        )
        # POM 4.0.0 sequences <properties> before <dependencies>; inserting after
        # would produce a document that fails schema validation.
        assert result.index("<properties>") < result.index("<dependencies>")
        assert "    <properties>" in result
        assert "        <jacoco.line.min>0.82</jacoco.line.min>" in result
        assert "    </properties>" in result

    def test_detect_indent(self):
        assert cf.detect_indent(POM_WITH_FLOORS) == "    "
        assert cf.detect_indent("<project>\n\t<modules>\n\t</modules>\n</project>") == "\t"

    def test_refresh_comment(self):
        result = cf.refresh_comment(POM_WITH_FLOORS, 81.4, 66.2, 3.0)
        assert "measured 81.4% line / 66.2% branch" in result
        assert "measured 79.2% line / 64.1% branch" not in result
        assert "Floors sit 3 points under" in result

    def test_refresh_comment_tracks_a_non_default_cushion(self):
        result = cf.refresh_comment(POM_WITH_FLOORS, 81.4, 66.2, 2.0)
        assert "Floors sit 2 points under" in result
        assert "Floors sit 3 points under" not in result


class TestApplyModule:
    def test_writes_floors_and_refreshes_the_comment(self, tmp_path):
        pom = tmp_path / "pom.xml"
        pom.write_text(POM_WITH_FLOORS, encoding="utf-8")
        target = module(line=(15, 85), branch=(30, 70), floor_line=0.76, floor_branch=0.61, pom=pom)

        assert cf.apply_module(target, cf.proposed_floors(target, options()), options()) is True

        written = pom.read_text(encoding="utf-8")
        assert "<jacoco.line.min>0.82</jacoco.line.min>" in written
        assert "<jacoco.branch.min>0.67</jacoco.branch.min>" in written
        assert "measured 85.0% line / 70.0% branch" in written

    def test_no_write_when_nothing_changes(self, tmp_path):
        pom = tmp_path / "pom.xml"
        pom.write_text(POM_WITH_FLOORS, encoding="utf-8")
        target = module(line=(21, 79), branch=(36, 64), floor_line=0.76, floor_branch=0.61, pom=pom)

        assert cf.apply_module(target, {}, options()) is False
        assert pom.read_text(encoding="utf-8") == POM_WITH_FLOORS

    def test_adds_floors_to_a_pom_that_had_none(self, tmp_path):
        pom = tmp_path / "pom.xml"
        pom.write_text(POM_WITHOUT_PROPERTIES, encoding="utf-8")
        target = module(line=(20, 80), branch=(35, 65), floor_line=None, floor_branch=None, pom=pom)

        assert cf.apply_module(target, cf.proposed_floors(target, options()), options()) is True

        written = pom.read_text(encoding="utf-8")
        assert "<jacoco.line.min>0.77</jacoco.line.min>" in written
        assert "<jacoco.branch.min>0.62</jacoco.branch.min>" in written
        assert written.index("<properties>") < written.index("<dependencies>")


class TestMain:
    """End-to-end over a throwaway reactor, exercising the CLI contract."""

    @pytest.fixture
    def reactor(self, tmp_path, monkeypatch):
        (tmp_path / "pom.xml").write_text(
            "<project>\n  <modules>\n    <module>pos-example</module>\n  </modules>\n</project>\n",
            encoding="utf-8",
        )
        module_dir = tmp_path / "pos-example"
        module_dir.mkdir()
        (module_dir / "pom.xml").write_text(POM_WITH_FLOORS, encoding="utf-8")
        monkeypatch.setattr(cf, "ROOT", tmp_path)
        return module_dir

    def test_check_passes_on_a_healthy_cushion(self, reactor, capsys):
        write_csv(reactor, [(36, 64, 21, 79)])
        assert cf.main(["--check"]) == 0
        assert "PASS" in capsys.readouterr().out

    def test_check_fails_on_a_stale_floor(self, reactor, capsys):
        write_csv(reactor, [(10, 90, 5, 95)])
        assert cf.main(["--check"]) == 1
        assert "STALE" in capsys.readouterr().out

    def test_check_fails_on_a_breach(self, reactor, capsys):
        write_csv(reactor, [(50, 50, 40, 60)])
        assert cf.main(["--check"]) == 1
        assert "BREACH" in capsys.readouterr().out

    def test_refuses_it_contaminated_coverage(self, reactor, capsys):
        write_csv(reactor, [(36, 64, 21, 79)])
        failsafe = reactor / "target" / "failsafe-reports"
        failsafe.mkdir(parents=True)
        (failsafe / "TEST-com.positivity.example.ThingIT.xml").write_text("<testsuite/>", encoding="utf-8")

        assert cf.main(["--check"]) == 1
        assert "Failsafe reports found" in capsys.readouterr().out
        assert cf.main(["--check", "--allow-its"]) == 0

    def test_errors_when_nothing_was_built(self, reactor, capsys):
        assert cf.main(["--check"]) == 1
        assert "no module has target/site/jacoco/jacoco.csv" in capsys.readouterr().out

    def test_dry_run_leaves_the_pom_alone(self, reactor, capsys):
        write_csv(reactor, [(10, 90, 5, 95)])
        before = (reactor / "pom.xml").read_text(encoding="utf-8")
        assert cf.main(["--dry-run"]) == 0
        assert (reactor / "pom.xml").read_text(encoding="utf-8") == before
        assert "would change" in capsys.readouterr().out

    def test_apply_writes_the_pom(self, reactor):
        write_csv(reactor, [(10, 90, 5, 95)])
        assert cf.main(["--apply"]) == 0
        written = (reactor / "pom.xml").read_text(encoding="utf-8")
        assert "<jacoco.line.min>0.92</jacoco.line.min>" in written
        assert "<jacoco.branch.min>0.87</jacoco.branch.min>" in written

    def test_dry_run_says_a_floor_would_drop(self, reactor, capsys):
        write_csv(reactor, [(50, 50, 40, 60)])
        assert cf.main(["--dry-run"]) == 0
        out = capsys.readouterr().out
        assert "--allow-lower" in out
        assert "jacoco.line.min 0.76 -> 0.57" in out

    def test_apply_then_check_is_clean(self, reactor):
        write_csv(reactor, [(10, 90, 5, 95)])
        assert cf.main(["--apply"]) == 0
        assert cf.main(["--check"]) == 0
