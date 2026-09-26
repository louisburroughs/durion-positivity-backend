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


def _postal_code_block():
    """The service_area_postal_codes section of the reference migration."""
    sql = _REFERENCE_SQL.read_text()
    return sql[sql.index("-- Service area postal codes."):sql.index("-- Capabilities")]


def _areas_with_postal_codes():
    return set(re.findall(r"WHERE sa\.name = '([^']+)'", _postal_code_block()))


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

    def test_capabilityCodesAreTierZeroOperationCodes(self):
        # CAP-325 D14: a unit claims catalog operation codes — the same vocabulary as a bay's specialty
        # claim — and pos-location validates them against its ext_catalog_service replica, which the
        # Tier 0 catalog pack fills. The location-owned capability registry is retired (V5).
        with (_SCRIPTS / "fixtures" / "seed" / "alpha" / "catalog" / "tier0-services.csv").open(newline="") as handle:
            operations = {row["operationCode"] for row in csv.DictReader(handle)}
        self.assertGreater(len(operations), 20)
        for row in _rows("mobile-units.csv"):
            for code in row["capabilityCodes"].split(";"):
                with self.subTest(unit=row["name"], code=code):
                    self.assertIn(code, operations)

    def test_serviceAreaNamesAreSeeded(self):
        seeded = _seeded("service_areas", 1)
        self.assertEqual(len(seeded), 27, f"expected the 27 seeded areas, got {len(seeded)}")
        for row in _rows("mobile-unit-coverage-rules.csv"):
            with self.subTest(unit=row["unitName"], area=row["serviceAreaName"]):
                self.assertIn(row["serviceAreaName"], seeded)

    def test_everyCoveredServiceAreaHasPostalCodes(self):
        """The eligibility query inner-joins serviceArea.postalCodes, so an area without them
        covers no address however many coverage rules point at it -- which is what kept
        GET /v1/mobile-units:eligible empty before #1986."""
        for row in _rows("mobile-unit-coverage-rules.csv"):
            with self.subTest(area=row["serviceAreaName"]):
                self.assertIn(row["serviceAreaName"], _areas_with_postal_codes())

    def test_postalCodeInsertsResolveTheAreaByNameAndStayTenantScoped(self):
        """Naming a literal area id would abort the migration -- and block pos-location's start --
        on any database where that area already exists under a different id, because the area
        inserts above are ON CONFLICT (tenant_id, name) DO NOTHING. The tenant predicate matters
        because Flyway runs as the owner, which bypasses row-level security."""
        block = _postal_code_block()
        self.assertNotIn("::uuid", block, "postal code rows must not name a literal service area id")
        self.assertEqual(block.count("SELECT sa.id, 'US', v.code"), 27)
        self.assertEqual(block.count("AND sa.tenant_id = public.app_current_tenant()"), 27)

    def test_postalCodesAreDisjointAcrossTheSeededAreas(self):
        """Overlap would make one address resolve to two areas, so the eligible list would carry
        units from both and the priority ordering would no longer describe one coverage map."""
        codes = re.findall(r"\('(\d{5})'\)", _postal_code_block())
        duplicated = sorted({code for code in codes if codes.count(code) > 1})
        self.assertEqual(duplicated, [], "a postal code is claimed by more than one service area")


class CoverageFixtureIntegrityTest(unittest.TestCase):
    def test_everyCoverageRowNamesAUnitThatExists(self):
        """A typo'd unitName silently leaves the real unit with no coverage: rejected as an opaque
        HTTP 400 if the unit is ACTIVE, and quietly under-covered if it is not."""
        units = {row["name"] for row in _rows("mobile-units.csv")}
        for row in _rows("mobile-unit-coverage-rules.csv"):
            with self.subTest(unit=row["unitName"]):
                self.assertIn(row["unitName"], units)

    def test_noTwoUnitsShareAPriorityOnTheSameServiceArea(self):
        """findEligibleCoverageRules orders by priority alone, so units tied on one area come back
        in whatever order Postgres returns and the demo result is not reproducible."""
        seen = {}
        for row in _rows("mobile-unit-coverage-rules.csv"):
            key = (row["serviceAreaName"], row["priority"])
            with self.subTest(area=row["serviceAreaName"], priority=row["priority"]):
                self.assertNotIn(key, seen, f"tied with {seen.get(key)}")
            seen[key] = row["unitName"]


class PackOrderTest(unittest.TestCase):
    """Bays and mobile units claim catalog operation codes that pos-location validates against its
    ext_catalog_service replica (CAP-325 D14), so both packs must run after the catalog services
    that publish those codes -- a bay seeded first would have every default claim refused."""

    def test_baysAndMobileUnitsFollowTheCatalogServices(self):
        paths = [path for path, _ in seed_alpha.PACK_FILES]
        services = paths.index("catalog/tier0-services.csv")
        self.assertGreater(paths.index("location/bays.csv"), services)
        self.assertGreater(paths.index("location/mobile-units.csv"), services)
        # Still ahead of anything that books against them.
        self.assertLess(paths.index("location/bays.csv"), paths.index("price/base-prices.csv"))


class LoaderPackClassificationTest(unittest.TestCase):
    """Pins the cross-language half of the move to an API pack.

    pos-bulk-loader's AlphaFixtureHeadersMapTest reads the real fixture off disk and asserts every
    column maps to a field of the domain's loader record. Leaving mobile-units.csv listed there
    failed on the two new columns -- and because CI only builds changed modules, that break would
    have landed on main and surfaced in an unrelated PR."""

    _JAVA_TEST = (_SCRIPTS.parent / "pos-bulk-loader/src/test/java/com/positivity/bulkloader"
                  / "internal/domain/AlphaFixtureHeadersMapTest.java")

    def test_mobileUnitsIsNotListedAsALoaderBackedPack(self):
        java = self._JAVA_TEST.read_text()
        listed = re.findall(r'Arguments\.of\("([^"]+)"', java)
        self.assertNotIn("location/mobile-units.csv", listed)
        self.assertNotIn("location/mobile-unit-coverage-rules.csv", listed)

    def test_theOtherApiPackIsAbsentTooSoTheConventionIsClear(self):
        java = self._JAVA_TEST.read_text()
        self.assertNotIn("location/site-defaults.csv", re.findall(r'Arguments\.of\("([^"]+)"', java))


class ActiveUnitCompletenessTest(unittest.TestCase):
    """The rule MobileUnitServiceImpl.validateCreateMobileUnitRequest enforces."""

    def test_everyActiveUnitCarriesPolicyCapabilitiesAndCoverage(self):
        covered = {row["unitName"] for row in _rows("mobile-unit-coverage-rules.csv")}
        active = [row for row in _rows("mobile-units.csv") if row["status"] == "ACTIVE"]
        self.assertEqual(len(active), 10, "10 of the 11 units are ACTIVE; MU-CLT-MAIN-03 stays parked")
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


class SdkSiteMobileUnitTest(unittest.TestCase):
    """The SDK integration suites place work on a mobile unit at their own site, ATX-RIV-001.

    pos-workorder's ServicePositionServiceImpl.resolvePosition refuses a unit based at any other
    site than the workorder's, and the SDK's createActiveMobileUnit builds its unit by copying the
    policy, capabilities and coverage rules of an ACTIVE unit already based there -- failing suite H
    in beforeAll, before H8 ever assigns, when there is none. Until these rows were added every
    unit in this fixture was a Charlotte unit, so that copy had nothing to find."""

    SDK_SITE = "ATX-RIV-001"

    def test_theSdkSiteHasAnActiveUnitWithPolicyCapabilitiesAndCoverage(self):
        covered = {row["unitName"] for row in _rows("mobile-unit-coverage-rules.csv")}
        at_site = [row for row in _rows("mobile-units.csv")
                   if row["baseLocationCode"] == self.SDK_SITE and row["status"] == "ACTIVE"
                   and row["travelBufferPolicyName"] and row["capabilityCodes"] and row["name"] in covered]
        self.assertTrue(at_site, f"no fully configured ACTIVE mobile unit is based at {self.SDK_SITE}")

    def test_theSdkSiteUnitsCoverTheSitesOwnPostalCode(self):
        """Coverage is geographic: a unit covering only Charlotte areas would be ACTIVE and still
        eligible for no Austin address, which is the case createActiveMobileUnit's same-site rule
        exists to avoid."""
        with (_FIXTURES / "locations.csv").open(newline="") as handle:
            site = next(row for row in csv.DictReader(handle) if row["code"] == self.SDK_SITE)
        block = _postal_code_block()
        areas_with_site_code = {
            area for area in _areas_with_postal_codes()
            if re.search(r"\('" + site["postalCode"] + r"'\)[^;]*WHERE sa\.name = '" + re.escape(area) + "'", block)}
        units = {row["name"] for row in _rows("mobile-units.csv") if row["baseLocationCode"] == self.SDK_SITE}
        for unit in units:
            with self.subTest(unit=unit):
                areas = {row["serviceAreaName"] for row in _rows("mobile-unit-coverage-rules.csv")
                         if row["unitName"] == unit}
                self.assertTrue(areas & areas_with_site_code,
                                f"{unit} covers no area holding the site's own postal code {site['postalCode']}")


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

    def __init__(self, existing_units=(), coverage_rules=None):
        """`existing_units` may be plain names (complete, seeded by this same pack) or dicts
        standing for whatever the service actually holds."""
        sql = _REFERENCE_SQL.read_text()
        self.areas = [{"id": mid, "name": name} for mid, name in _seeded_pairs(sql, "service_areas")]
        self.policies = [
            {"id": mid, "name": name} for mid, name in _seeded_pairs(sql, "travel_buffer_policies")]
        self.existing = [_as_unit(unit) for unit in existing_units]
        self.coverage_rules = coverage_rules if coverage_rules is not None else {}
        self.posted = []
        self.writes = []
        self.put_status = 200
        self.patch_status = 200

    def get(self, path, allow_error=False):
        if path == "/location/locations":
            return 200, [{"code": code, "id": f"loc-{code}"} for code in (
                "CLT-MAIN-001", "CLT-NORTH-001", "CLT-SOUTH-001", "CLT-MOB-HUB-001", "CORP-HQ-001",
                "ATX-RIV-001")]
        if path == "/location/travel-buffer-policies":
            return 200, self.policies
        if path == "/location/service-areas":
            return 200, self.areas
        if path.startswith("/location/mobile-units?"):
            pages = getattr(self, "pages", 1)
            index = int(path.split("page=")[1].split("&")[0])
            size = max(1, -(-len(self.existing) // pages))
            return 200, {"content": self.existing[index * size:(index + 1) * size],
                         "totalPages": pages}
        if path.startswith("/location/mobile-units/") and path.endswith("/coverage-rules"):
            unit_id = path.split("/")[3]
            return 200, self.coverage_rules.get(unit_id, [])
        raise AssertionError(f"unexpected GET {path}")

    def post_json(self, path, body, allow_error=False):
        assert path == "/location/mobile-units", path
        self.posted.append(body)
        return 201, {"id": "new"}

    def put_json(self, path, body, allow_error=False):
        assert path.startswith("/location/mobile-units/") and path.endswith("/coverage-rules"), path
        self.writes.append(("PUT", path, body))
        return self.put_status, None

    def patch_json(self, path, body, allow_error=False):
        assert path.startswith("/location/mobile-units/"), path
        self.writes.append(("PATCH", path, body))
        return self.patch_status, None


def _as_unit(unit):
    """A bare name means a unit this pack itself seeded: ACTIVE, with a policy and capabilities."""
    if isinstance(unit, dict):
        return {"id": unit.get("id", f"id-{unit['name']}"), **unit}
    return {
        "id": f"id-{unit}",
        "name": unit,
        "status": "ACTIVE",
        "travelBufferPolicyId": "policy-1",
        "serviceCapabilityCodes": ["cap-1"],
    }


def _seeded_pairs(sql, table):
    return re.findall(
        r"INSERT INTO " + table + r" \([^)]*\)\nVALUES \('([0-9a-f-]+)'::uuid, '([^']*)'", sql)


class MobileUnitPackTest(unittest.TestCase):
    """run_mobile_units end to end against a stub gateway."""

    def _run(self, gateway):
        return seed_alpha.run_mobile_units(gateway, "location/mobile-units.csv", None)

    def test_everyUnitIsPostedAndThePackReportsSuccess(self):
        gateway = _StubGateway()
        self.assertTrue(self._run(gateway))
        self.assertEqual(len(gateway.posted), 11)

    def test_theParkedUnitIsNotAFailureForHavingNoCoverageRules(self):
        """Absent from the coverage fixture is not the same as unresolvable in it. Conflating the
        two dropped MU-CLT-MAIN-03 and reported 8/9 with a failure, for a unit that is correct as
        written -- only an ACTIVE unit needs rules."""
        gateway = _StubGateway()
        self.assertTrue(self._run(gateway))
        parked = next(body for body in gateway.posted if body["name"] == "MU-CLT-MAIN-03")
        self.assertEqual(parked["status"], "INACTIVE")
        self.assertEqual(parked["coverageRules"], [])
        self.assertTrue(parked["serviceCapabilityCodes"], "parked, but still an equipped van")

    def test_everyActivePayloadCarriesTheTrioTheServiceDemands(self):
        gateway = _StubGateway()
        self._run(gateway)
        for body in (b for b in gateway.posted if b["status"] == "ACTIVE"):
            with self.subTest(unit=body["name"]):
                self.assertTrue(body.get("travelBufferPolicyId"))
                self.assertTrue(body["serviceCapabilityCodes"])
                self.assertTrue(body["coverageRules"])

    def test_existingUnitsAreSkippedSoARerunAddsOnlyWhatIsMissing(self):
        gateway = _StubGateway(
            existing_units=["MU-CLT-MAIN-01", "MU-Charlotte-02"],
            coverage_rules={"id-MU-CLT-MAIN-01": [{"id": "r"}], "id-MU-Charlotte-02": [{"id": "r"}]})
        self.assertTrue(self._run(gateway))
        self.assertEqual([body["name"] for body in gateway.posted],
                         ["MU-CLT-MAIN-02", "MU-CLT-MAIN-03", "MU-CLT-NORTH-01", "MU-CLT-NORTH-02",
                          "MU-CLT-SOUTH-01", "MU-CLT-SOUTH-02", "MU-Charlotte-01", "MU-ATX-RIV-01",
                          "MU-ATX-RIV-02"])

    def test_capabilityCodesAreSplitOnSemicolonsAndBlanksDropped(self):
        """A trailing or doubled `;` would otherwise reach the service as an empty capability,
        which resolveCapabilityIds rejects with "Invalid serviceCapabilityCodes: <blank>" -- failing the
        whole unit over a stray separator."""
        gateway = _StubGateway()
        self._run(gateway)
        by_name = {body["name"]: body for body in gateway.posted}
        for row in _rows("mobile-units.csv"):
            with self.subTest(unit=row["name"]):
                expected = [code for code in row["capabilityCodes"].split(";") if code]
                self.assertEqual(by_name[row["name"]]["serviceCapabilityCodes"], expected)
                self.assertNotIn("", by_name[row["name"]]["serviceCapabilityCodes"])

    def test_multiplePagesOfExistingUnitsAreAllRead(self):
        """The skip check is only sound if it sees every unit; a reader that stops after page 0
        would re-POST everything past the first page and collect 409s."""
        gateway = _StubGateway()
        gateway.existing = [_as_unit(f"MU-{index}") for index in range(150)]
        gateway.pages = 2
        found = seed_alpha.existing_mobile_units(gateway)
        self.assertEqual(len(found), 150)

    def test_totalPagesIsReadUnderEitherSpringSerialisation(self):
        """Spring nests the page metadata under `page` in VIA_DTO mode and emits it flat otherwise."""
        self.assertEqual(seed_alpha.total_pages({"totalPages": 3}), 3)
        self.assertEqual(seed_alpha.total_pages({"page": {"totalPages": 4}}), 4)
        self.assertEqual(seed_alpha.total_pages({}), 1)
        self.assertEqual(seed_alpha.total_pages(None), 1)


class LegacyIncompleteUnitTest(unittest.TestCase):
    """An alpha seeded before #1986 carries all nine names as INACTIVE units with no policy,
    capabilities or coverage (#1982 parked them). Skipping on the name alone left no unit active, so
    the pack completes each unit its fixture wants ACTIVE in place -- coverage PUT, then one PATCH
    carrying the policy, the capabilities and the status flip -- and leaves the parked one alone."""

    def _legacy_nine(self):
        return [{"name": row["name"], "status": "INACTIVE", "travelBufferPolicyId": None,
                 "serviceCapabilityCodes": []} for row in _rows("mobile-units.csv")]

    def _run(self, gateway):
        return seed_alpha.run_mobile_units(gateway, "location/mobile-units.csv", None)

    def _active_rows(self):
        return [row for row in _rows("mobile-units.csv") if row["status"] == "ACTIVE"]

    def test_aLegacySeededAlphaActivatesEveryUnitExceptTheParkedOne(self):
        gateway = _StubGateway(existing_units=self._legacy_nine())
        self.assertTrue(self._run(gateway))
        self.assertEqual(gateway.posted, [], "the names exist; posting would 409 per row")
        activated = {path.split("/")[3] for verb, path, body in gateway.writes
                     if verb == "PATCH" and body["status"] == "ACTIVE"}
        self.assertEqual(activated, {f"id-{row['name']}" for row in self._active_rows()})
        self.assertEqual(len(activated), 10)
        self.assertNotIn("id-MU-CLT-MAIN-03", {path.split("/")[3] for _, path, _ in gateway.writes})

    def test_coverageIsReplacedBeforeTheStatusFlipSoThePatchPassesTheActiveCheck(self):
        """PATCH validates the merged unit (requireCompleteWhenActive reads the rules back), so the
        rules must already be there when the ACTIVE status arrives."""
        gateway = _StubGateway(existing_units=self._legacy_nine())
        self._run(gateway)
        for row in self._active_rows():
            with self.subTest(unit=row["name"]):
                verbs = [verb for verb, path, _ in gateway.writes if path.split("/")[3] == f"id-{row['name']}"]
                self.assertEqual(verbs, ["PUT", "PATCH"])

    def test_theActivatingPatchCarriesTheFixturesPolicyCapabilitiesAndRules(self):
        gateway = _StubGateway(existing_units=self._legacy_nine())
        self._run(gateway)
        policy_ids = {policy["name"]: policy["id"] for policy in gateway.policies}
        writes = {(verb, path.split("/")[3]): body for verb, path, body in gateway.writes}
        coverage_counts = {}
        for rule in _rows("mobile-unit-coverage-rules.csv"):
            coverage_counts[rule["unitName"]] = coverage_counts.get(rule["unitName"], 0) + 1
        for row in self._active_rows():
            with self.subTest(unit=row["name"]):
                patch = writes[("PATCH", f"id-{row['name']}")]
                self.assertEqual(patch["travelBufferPolicyId"], policy_ids[row["travelBufferPolicyName"]])
                self.assertEqual(patch["serviceCapabilityCodes"],
                                 [code for code in row["capabilityCodes"].split(";") if code])
                self.assertEqual(len(writes[("PUT", f"id-{row['name']}")]["rules"]),
                                 coverage_counts[row["name"]])

    def test_aRejectedActivationIsReportedAsAFailure(self):
        gateway = _StubGateway(existing_units=self._legacy_nine())
        gateway.patch_status = 422
        self.assertFalse(self._run(gateway))

    def test_aRejectedCoverageReplaceSkipsTheActivation(self):
        gateway = _StubGateway(existing_units=self._legacy_nine())
        gateway.put_status = 400
        self.assertFalse(self._run(gateway))
        self.assertEqual([verb for verb, _, _ in gateway.writes], ["PUT"] * 10)

    def test_theParkedUnitIsNotFlaggedBecauseTheFixtureOnlyWantsItToExist(self):
        """MU-CLT-MAIN-03 is INACTIVE in the fixture too, so a legacy INACTIVE row already matches
        what is asked for -- flagging it would demand a reset for a unit that is correct."""
        parked = next(unit for unit in self._legacy_nine() if unit["name"] == "MU-CLT-MAIN-03")
        row = next(r for r in _rows("mobile-units.csv") if r["name"] == "MU-CLT-MAIN-03")
        self.assertIsNone(seed_alpha.mobile_unit_shortfall(_StubGateway(), parked, row, []))

    def test_eachWayOfBeingIncompleteIsNamedInTheMessage(self):
        row = next(r for r in _rows("mobile-units.csv") if r["name"] == "MU-CLT-MAIN-01")
        complete = {"id": "u1", "name": row["name"], "status": "ACTIVE",
                    "travelBufferPolicyId": "p1", "serviceCapabilityCodes": ["c1"]}
        gateway = _StubGateway(coverage_rules={"u1": [{"id": "r1"}]})

        self.assertIsNone(seed_alpha.mobile_unit_shortfall(gateway, complete, row, [{"ruleType": "X"}]))
        self.assertIn("is not ACTIVE", seed_alpha.mobile_unit_shortfall(
            gateway, {**complete, "status": "INACTIVE"}, row, []))
        self.assertIn("no travel buffer policy", seed_alpha.mobile_unit_shortfall(
            gateway, {**complete, "travelBufferPolicyId": None}, row, []))
        self.assertIn("no capabilities", seed_alpha.mobile_unit_shortfall(
            gateway, {**complete, "serviceCapabilityCodes": []}, row, []))

    def test_anActiveUnitThatLostItsCoverageRulesIsFlagged(self):
        """Coverage is the one part the list response does not carry, and the one part that decides
        whether `:eligible` returns anything."""
        row = next(r for r in _rows("mobile-units.csv") if r["name"] == "MU-CLT-MAIN-01")
        unit = {"id": "u1", "name": row["name"], "status": "ACTIVE",
                "travelBufferPolicyId": "p1", "serviceCapabilityCodes": ["c1"]}
        gateway = _StubGateway(coverage_rules={"u1": []})
        self.assertIn("no coverage rules",
                      seed_alpha.mobile_unit_shortfall(gateway, unit, row, [{"ruleType": "X"}]))

    def test_aUnitThisPackAlreadySeededIsSkippedNotReseeded(self):
        """The legitimate idempotent case: running the new pack twice changes nothing and passes."""
        gateway = _StubGateway(
            existing_units=[row["name"] for row in _rows("mobile-units.csv")],
            coverage_rules={f"id-{row['unitName']}": [{"id": "r"}]
                            for row in _rows("mobile-unit-coverage-rules.csv")})
        self.assertTrue(self._run(gateway))
        self.assertEqual(gateway.posted, [])


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
