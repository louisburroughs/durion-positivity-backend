"""Pins the service-skill-requirements pack (CAP-329, spec D8/D13): the fixture's references and
the requests the driver assembles from them.

The pack is an API pack, not a loader pack: PUT /v1/products/services/{id}/requirements replaces
a service's whole requirement set, so the rows are grouped by operation and each operation is sent
once. Three things are worth pinning without a gateway:

  * Every operation the fixture names is a Tier 0 service and every skill it names is a registry
    row seeded by pos-people's R__seed_people_2_skill_registry.sql. Both are resolved live on alpha
    (by service name and by skill code), so a typo here is a per-row WARN there and nothing at all
    without this test.

  * A class range is either both bounds blank (ANY) or both set with 1 <= min <= max <= 8, and lies
    inside the skill's own declared range — the catalog refuses anything else with 422.

  * The driver sends one PUT per operation carrying every row for it, with blank bounds as JSON
    null rather than empty strings, and an unknown skill poisons only its own operation.

Stdlib only: no build, no network, no gateway.
"""

import csv
import importlib.util
import pathlib
import re
import unittest

_SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
_DRIVER_PATH = _SCRIPTS / "seed-alpha.py"
_CATALOG_FIXTURES = _SCRIPTS / "fixtures" / "seed" / "alpha" / "catalog"
_REGISTRY_SQL = (
    _SCRIPTS.parent
    / "pos-people"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
    / "R__seed_people_2_skill_registry.sql"
)
_PACK = "catalog/tier0-service-skill-requirements.csv"


def _load_driver():
    spec = importlib.util.spec_from_file_location("seed_alpha_skill_requirements", _DRIVER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


seed_alpha = _load_driver()


def _rows(name):
    with (_CATALOG_FIXTURES / name).open(newline="") as handle:
        return list(csv.DictReader(handle))


def _registry_ranges():
    """code -> (min, max) for every skill row the registry migration seeds."""
    sql = _REGISTRY_SQL.read_text()
    return {
        code: (int(low), int(high))
        for code, low, high in re.findall(r"\('([A-Z0-9_-]+)',\s*'[^']*',\s*'[A-Z_]+',\s*(\d),\s*(\d)\)", sql)
    }


class FixtureReferenceTest(unittest.TestCase):
    def setUp(self):
        self.rows = _rows("tier0-service-skill-requirements.csv")
        self.operations = {row["operationCode"] for row in _rows("tier0-services.csv")}
        self.registry = _registry_ranges()

    def test_everyOperationIsATierZeroService(self):
        self.assertTrue(self.rows)
        for row in self.rows:
            self.assertIn(row["operationCode"], self.operations, row)

    def test_everySkillIsARegistryRow(self):
        self.assertGreater(len(self.registry), 10, "registry regex found too few rows; check the migration format")
        for row in self.rows:
            self.assertIn(row["skillCode"], self.registry, row)

    def test_classRangesAreWellFormedAndInsideTheSkillsOwnRange(self):
        for row in self.rows:
            low, high = row["minGvwrClass"], row["maxGvwrClass"]
            if not low and not high:
                continue  # ANY
            self.assertTrue(low and high, f"both bounds or neither: {row}")
            low, high = int(low), int(high)
            self.assertTrue(1 <= low <= high <= 8, row)
            skill_low, skill_high = self.registry[row["skillCode"]]
            self.assertTrue(skill_low <= low and high <= skill_high, f"outside the skill's own range: {row}")

    def test_noOperationNamesOneSkillTwice(self):
        seen = set()
        for row in self.rows:
            key = (row["operationCode"], row["skillCode"])
            self.assertNotIn(key, seen, row)
            seen.add(key)

    def test_theBrakeJobIsClassForkedAndTheInspectionIsAnyClass(self):
        by_operation = {}
        for row in self.rows:
            by_operation.setdefault(row["operationCode"], []).append(row)
        brakes = {row["skillCode"]: (row["minGvwrClass"], row["maxGvwrClass"]) for row in by_operation["BRAKE-PAD-REPLACE-FRONT"]}
        self.assertEqual(brakes, {"BRAKES-LIGHT": ("1", "3"), "BRAKES-MEDIUM_HEAVY": ("4", "8")})
        dot = by_operation["DOT-ANNUAL-INSPECTION"]
        self.assertEqual([(row["skillCode"], row["minGvwrClass"], row["maxGvwrClass"]) for row in dot], [("DOT-INSPECTOR", "", "")])


class LoaderPackClassificationTest(unittest.TestCase):
    def test_thePackIsAnApiPackListedAfterTheServicesItNames(self):
        paths = [path for path, _ in seed_alpha.PACK_FILES]
        domains = dict(seed_alpha.PACK_FILES)
        self.assertEqual(domains[_PACK], "@service-skill-requirements")
        self.assertIn("@service-skill-requirements", seed_alpha.API_PACKS)
        self.assertGreater(paths.index(_PACK), paths.index("catalog/tier0-services.csv"))
        self.assertGreater(paths.index(_PACK), paths.index("people/employees.csv"))


class _StubGateway:
    """Answers the three reads the pack makes and records every PUT."""

    def __init__(self, skills, unknown_service_names=()):
        self.skills = skills
        self.unknown = set(unknown_service_names)
        self.puts = []

    def get(self, path, allow_error=False):
        if path == "/people/people/skills":
            return 200, [{"skillId": f"skill-{code}", "code": code} for code in self.skills]
        prefix = "/catalog/products/services/name/"
        self.assertTrue(path.startswith(prefix))
        name = seed_alpha.urllib.parse.unquote(path[len(prefix):])
        if name in self.unknown:
            return 200, []
        return 200, [{"id": f"service-{name}", "name": name}]

    def assertTrue(self, condition):
        if not condition:
            raise AssertionError("unexpected gateway read")

    def put_json(self, path, body, allow_error=False):
        self.puts.append((path, body))
        return 200, {"id": path.split("/")[-2]}


class RequirementAssemblyTest(unittest.TestCase):
    def setUp(self):
        self.registry = list(_registry_ranges())
        self.names = {row["operationCode"]: row["name"] for row in _rows("tier0-services.csv")}
        self.rows = _rows("tier0-service-skill-requirements.csv")

    def test_onePutPerOperationCarryingEveryRowWithBlankBoundsAsNull(self):
        gateway = _StubGateway(self.registry)

        ok = seed_alpha.run_service_skill_requirements(gateway, _PACK, None)

        self.assertTrue(ok)
        operations = {row["operationCode"] for row in self.rows}
        self.assertEqual(len(gateway.puts), len(operations))
        by_service = {path.split("/")[-2]: body for path, body in gateway.puts}
        brake = by_service[f"service-{self.names['BRAKE-PAD-REPLACE-FRONT']}"]["requiredSkills"]
        self.assertEqual(
            brake,
            [
                {"skillId": "skill-BRAKES-LIGHT", "minGvwrClass": 1, "maxGvwrClass": 3},
                {"skillId": "skill-BRAKES-MEDIUM_HEAVY", "minGvwrClass": 4, "maxGvwrClass": 8},
            ],
        )
        dot = by_service[f"service-{self.names['DOT-ANNUAL-INSPECTION']}"]["requiredSkills"]
        self.assertEqual(dot, [{"skillId": "skill-DOT-INSPECTOR", "minGvwrClass": None, "maxGvwrClass": None}])
        for path, _ in gateway.puts:
            self.assertTrue(path.endswith("/requirements"))

    def test_anUnknownSkillPoisonsOnlyItsOperation(self):
        gateway = _StubGateway([code for code in self.registry if code != "DOT-INSPECTOR"])

        ok = seed_alpha.run_service_skill_requirements(gateway, _PACK, None)

        self.assertFalse(ok)
        operations = {row["operationCode"] for row in self.rows}
        self.assertEqual(len(gateway.puts), len(operations) - 1)
        self.assertNotIn(f"service-{self.names['DOT-ANNUAL-INSPECTION']}", {p.split("/")[-2] for p, _ in gateway.puts})

    def test_aServiceTheCatalogDoesNotKnowIsReportedNotSent(self):
        gateway = _StubGateway(self.registry, unknown_service_names={self.names["WHEEL-ALIGNMENT-4-WHEEL"]})

        ok = seed_alpha.run_service_skill_requirements(gateway, _PACK, None)

        self.assertFalse(ok)
        self.assertNotIn(f"service-{self.names['WHEEL-ALIGNMENT-4-WHEEL']}", {p.split("/")[-2] for p, _ in gateway.puts})


if __name__ == "__main__":
    unittest.main()
