"""Pins the mobile-unit pack's payload assembly and the fixtures it reads (issue #1986).

pos-location refuses an ACTIVE mobile unit that arrives without a travel buffer policy,
capabilities and coverage rules all three, so eight of the nine rows failed on every run until
#1982 parked them INACTIVE. The driver now assembles the whole unit and POSTs it in one call, which
puts three things worth pinning in reach of a stdlib test:

  * Row order inside a unit's coverage rules. MobileUnitServiceImpl.validateDistanceTiers walks the
    list as given and requires strictly ascending maxDistance ending in a single null catch-all --
    and once any one rule on the unit is DISTANCE_TIER it applies to every rule on that unit. A
    regrouping that looks harmless (a dict that loses order, a sort by priority) rejects the very
    fixture written to satisfy it, and only against a live pos-location.

  * The reference names the fixtures key off. Travel buffer policies, service areas and capability
    codes are seeded by R__seed_location_1_reference.sql; a typo in either CSV is a per-row failure
    on alpha and nothing at all here, so the names are checked against the migration directly.

  * That every ACTIVE row carries all three, which is the rule the service enforces.

Stdlib only: no build, no network, no gateway.
"""

import csv
import importlib.util
import pathlib
import re
import unittest

_SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
_DRIVER_PATH = _SCRIPTS / "seed-alpha.py"
_FIXTURES = _SCRIPTS / "fixtures" / "seed" / "alpha" / "location"
_REFERENCE_SQL = (
    _SCRIPTS.parent
    / "pos-location"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
    / "R__seed_location_1_reference.sql"
)


def _load_driver():
    spec = importlib.util.spec_from_file_location("seed_alpha_mobile_units", _DRIVER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


seed_alpha = _load_driver()


def _rows(name):
    with open(_FIXTURES / name, newline="") as fh:
        return list(csv.DictReader(fh))


def _seeded(table, column_index):
    """The literal values of one column of a table's INSERTs in the reference migration."""
    sql = _REFERENCE_SQL.read_text()
    pattern = re.compile(
        r"INSERT INTO " + table + r" \([^)]*\)\s*\nVALUES \(([^\n]*?)\)\s*\nON CONFLICT", re.MULTILINE)
    values = set()
    for match in pattern.finditer(sql):
        fields = next(csv.reader([match.group(1)], skipinitialspace=True))
        values.add(fields[column_index].strip().strip("'"))
    return values


class FixtureReferenceNamesTest(unittest.TestCase):
    """Every name the two fixtures carry is one the reference migration actually seeds."""

    def test_travelBufferPolicyNamesAreSeeded(self):
        seeded = _seeded("travel_buffer_policies", 1)
        self.assertEqual(len(seeded), 3, f"expected the 3 seeded policies, got {sorted(seeded)}")
        for row in _rows("mobile-units.csv"):
            with self.subTest(unit=row["name"]):
                self.assertIn(row["travelBufferPolicyName"], seeded)

    def test_capabilityCodesAreSeeded(self):
        seeded = _seeded("service_location_capabilities", 1)
        self.assertEqual(len(seeded), 20, f"expected the 20 seeded capabilities, got {len(seeded)}")
        for row in _rows("mobile-units.csv"):
            for code in row["capabilityCodes"].split(";"):
                with self.subTest(unit=row["name"], code=code):
                    self.assertIn(code, seeded)

    def test_serviceAreaNamesAreSeeded(self):
        seeded = _seeded("service_areas", 1)
        self.assertEqual(len(seeded), 25, f"expected the 25 seeded areas, got {len(seeded)}")
        for row in _rows("mobile-unit-coverage-rules.csv"):
            with self.subTest(unit=row["unitName"], area=row["serviceAreaName"]):
                self.assertIn(row["serviceAreaName"], seeded)

    def test_everyCoveredServiceAreaHasPostalCodes(self):
        """The eligibility query inner-joins serviceArea.postalCodes, so an area without them
        covers no address however many coverage rules point at it -- which is what kept
        GET /v1/mobile-units:eligible empty before #1986."""
        sql = _REFERENCE_SQL.read_text()
        areas_by_name = {}
        for match in re.finditer(
                r"INSERT INTO service_areas \([^)]*\)\s*\nVALUES \('([0-9a-f-]+)'::uuid, '([^']*)'", sql):
            areas_by_name[match.group(2)] = match.group(1)
        with_codes = set(re.findall(r"\('([0-9a-f-]+)'::uuid, '[A-Z]{2}', '[^']+'\)", sql))
        for row in _rows("mobile-unit-coverage-rules.csv"):
            with self.subTest(area=row["serviceAreaName"]):
                self.assertIn(areas_by_name[row["serviceAreaName"]], with_codes)


class ActiveUnitCompletenessTest(unittest.TestCase):
    """The rule MobileUnitServiceImpl.validateCreateMobileUnitRequest enforces."""

    def test_everyActiveUnitCarriesPolicyCapabilitiesAndCoverage(self):
        covered = {row["unitName"] for row in _rows("mobile-unit-coverage-rules.csv")}
        active = [row for row in _rows("mobile-units.csv") if row["status"] == "ACTIVE"]
        self.assertEqual(len(active), 8, "8 of the 9 units are ACTIVE; MU-CLT-MAIN-03 stays parked")
        for row in active:
            with self.subTest(unit=row["name"]):
                self.assertTrue(row["travelBufferPolicyName"])
                self.assertTrue(row["capabilityCodes"])
                self.assertIn(row["name"], covered)

    def test_theParkedUnitKeepsTheInactivePathCovered(self):
        parked = [row for row in _rows("mobile-units.csv") if row["status"] != "ACTIVE"]
        self.assertEqual([row["name"] for row in parked], ["MU-CLT-MAIN-03"])
        covered = {row["unitName"] for row in _rows("mobile-unit-coverage-rules.csv")}
        self.assertNotIn("MU-CLT-MAIN-03", covered)


class CoverageRuleAssemblyTest(unittest.TestCase):
    """seed_alpha.coverage_rules_by_unit: grouping, ordering, and typed fields."""

    def _built(self):
        areas = {row["serviceAreaName"]: f"area-{row['serviceAreaName']}"
                 for row in _rows("mobile-unit-coverage-rules.csv")}
        return seed_alpha.coverage_rules_by_unit(areas)

    def test_rulesKeepFixtureRowOrderWithinAUnit(self):
        built = self._built()
        fixture_order = {}
        for row in _rows("mobile-unit-coverage-rules.csv"):
            fixture_order.setdefault(row["unitName"], []).append(row["serviceAreaName"])
        for unit, area_names in fixture_order.items():
            with self.subTest(unit=unit):
                self.assertEqual([rule["serviceAreaId"] for rule in built[unit]],
                                 [f"area-{name}" for name in area_names])

    def test_distanceTierUnitsSatisfyTheAscendingCatchAllRule(self):
        """Mirrors MobileUnitServiceImpl.validateDistanceTiers: strictly ascending, one trailing
        null catch-all, applied across every rule on a unit that has any DISTANCE_TIER rule."""
        for unit, rules in self._built().items():
            if not any(rule["ruleType"] == "DISTANCE_TIER" for rule in rules):
                continue
            with self.subTest(unit=unit):
                distances = [rule.get("maxDistance") for rule in rules]
                self.assertIsNone(distances[-1], "the last tier must be the null catch-all")
                self.assertNotIn(None, distances[:-1], "only the last tier may be the catch-all")
                ascending = [float(distance) for distance in distances[:-1]]
                self.assertEqual(ascending, sorted(set(ascending)), "tiers must be strictly ascending")

    def test_blankMaxDistanceAndDatesAreOmittedRatherThanSentAsEmptyStrings(self):
        """maxDistance is a BigDecimal and validFrom/validTo are LocalDate on CoverageRuleRequest;
        an empty string is a 400, and a maxDistance of "" would also read as a non-null tier."""
        for unit, rules in self._built().items():
            for index, rule in enumerate(rules):
                with self.subTest(unit=unit, rule=index):
                    for field in ("maxDistance", "validFrom", "validTo"):
                        self.assertNotEqual(rule.get(field), "")
                    self.assertIsInstance(rule["priority"], int)

    def test_aRuleNamingAnUnknownServiceAreaPoisonsThatUnitOnly(self):
        """Sending the remaining rules would give the unit narrower coverage than the fixture
        describes, silently -- so the unit is skipped and every other unit still loads."""
        areas = {row["serviceAreaName"]: f"area-{row['serviceAreaName']}"
                 for row in _rows("mobile-unit-coverage-rules.csv")}
        del areas["Rock Hill"]
        built = seed_alpha.coverage_rules_by_unit(areas)
        self.assertIsNone(built["MU-CLT-SOUTH-02"], "the unit whose rule names the missing area")
        self.assertIsNotNone(built["MU-CLT-SOUTH-01"], "every other unit is unaffected")


class _StubGateway:
    """The three GETs the pack makes, answered from the reference migration."""

    base_url = "https://alpha.example"

    def __init__(self, existing_units=()):
        sql = _REFERENCE_SQL.read_text()
        self.areas = [{"id": mid, "name": name} for mid, name in _seeded_pairs(sql, "service_areas")]
        self.policies = [
            {"id": mid, "name": name} for mid, name in _seeded_pairs(sql, "travel_buffer_policies")]
        self.existing = [{"name": name} for name in existing_units]
        self.posted = []

    def get(self, path, allow_error=False):
        if path == "/location/locations":
            return 200, [{"code": code, "id": f"loc-{code}"} for code in (
                "CLT-MAIN-001", "CLT-NORTH-001", "CLT-SOUTH-001", "CLT-MOB-HUB-001", "CORP-HQ-001")]
        if path == "/location/travel-buffer-policies":
            return 200, self.policies
        if path == "/location/service-areas":
            return 200, self.areas
        if path.startswith("/location/mobile-units?"):
            return 200, {"content": self.existing, "totalPages": 1}
        raise AssertionError(f"unexpected GET {path}")

    def post_json(self, path, body, allow_error=False):
        assert path == "/location/mobile-units", path
        self.posted.append(body)
        return 201, {"id": "new"}


def _seeded_pairs(sql, table):
    return re.findall(
        r"INSERT INTO " + table + r" \([^)]*\)\nVALUES \('([0-9a-f-]+)'::uuid, '([^']*)'", sql)


class MobileUnitPackTest(unittest.TestCase):
    """run_mobile_units end to end against a stub gateway."""

    def _run(self, gateway):
        return seed_alpha.run_mobile_units(gateway, "location/mobile-units.csv", None)

    def test_allNineUnitsArePostedAndThePackReportsSuccess(self):
        gateway = _StubGateway()
        self.assertTrue(self._run(gateway))
        self.assertEqual(len(gateway.posted), 9)

    def test_theParkedUnitIsNotAFailureForHavingNoCoverageRules(self):
        """Absent from the coverage fixture is not the same as unresolvable in it. Conflating the
        two dropped MU-CLT-MAIN-03 and reported 8/9 with a failure, for a unit that is correct as
        written -- only an ACTIVE unit needs rules."""
        gateway = _StubGateway()
        self.assertTrue(self._run(gateway))
        parked = next(body for body in gateway.posted if body["name"] == "MU-CLT-MAIN-03")
        self.assertEqual(parked["status"], "INACTIVE")
        self.assertEqual(parked["coverageRules"], [])
        self.assertTrue(parked["capabilityIds"], "parked, but still an equipped van")

    def test_everyActivePayloadCarriesTheTrioTheServiceDemands(self):
        gateway = _StubGateway()
        self._run(gateway)
        for body in (b for b in gateway.posted if b["status"] == "ACTIVE"):
            with self.subTest(unit=body["name"]):
                self.assertTrue(body.get("travelBufferPolicyId"))
                self.assertTrue(body["capabilityIds"])
                self.assertTrue(body["coverageRules"])

    def test_existingUnitsAreSkippedSoARerunAddsOnlyWhatIsMissing(self):
        gateway = _StubGateway(existing_units=["MU-CLT-MAIN-01", "MU-Charlotte-02"])
        self.assertTrue(self._run(gateway))
        self.assertEqual([body["name"] for body in gateway.posted],
                         ["MU-CLT-MAIN-02", "MU-CLT-MAIN-03", "MU-CLT-NORTH-01", "MU-CLT-NORTH-02",
                          "MU-CLT-SOUTH-01", "MU-CLT-SOUTH-02", "MU-Charlotte-01"])

    def test_capabilitiesAreSentAsCodesNotEmptyStrings(self):
        gateway = _StubGateway()
        self._run(gateway)
        for body in gateway.posted:
            with self.subTest(unit=body["name"]):
                self.assertNotIn("", body["capabilityIds"])
                self.assertTrue(all(code == code.upper() for code in body["capabilityIds"]))


class PackRegistrationTest(unittest.TestCase):
    def test_mobileUnitsIsAnApiPackNotABulkLoaderDomain(self):
        """The loader's MOBILE_UNIT strategy carries only name/baseLocationCode/status/notes, so it
        cannot express an ACTIVE unit at all."""
        domains = dict(seed_alpha.PACK_FILES)
        self.assertEqual(domains["location/mobile-units.csv"], "@mobile-units")
        self.assertIn("@mobile-units", seed_alpha.API_PACKS)

    def test_mobileUnitsRunAfterTheLocationsThatBaseThem(self):
        order = [path for path, _ in seed_alpha.PACK_FILES]
        self.assertLess(order.index("location/locations.csv"), order.index("location/mobile-units.csv"))


if __name__ == "__main__":
    unittest.main()
