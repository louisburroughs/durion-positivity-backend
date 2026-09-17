"""Pins the operating-hours pack: the fixture's references and the requests the driver builds.

The hours are what make pos-shop-manager's capacity calendar answer at all — a location whose
operatingHours were never published reports every requested date UNAVAILABLE — and they are not
expressible through the LOCATION loader domain, so this is an API pack: one
PATCH /v1/locations/{id} per site carrying that site's whole week.

Four things are worth pinning without a gateway:

  * Every site named here is a site locations.csv creates, and is one of the four that have bays
    and a shop row. A shop with no hours has a calendar that cannot assemble a day; hours on a
    warehouse would advertise a bookable day no bay backs.

  * Each row carries both times, in order, because pos-location refuses an entry missing either
    (422) — and a rejected PATCH leaves the site with no hours at all, not with the good rows.

  * A day appears at most once per site (pos-location rejects a duplicate dayOfWeek), and Sunday
    appears nowhere: a day with no entry is CLOSED to pos-shop-manager, which is what the
    fixture means by leaving it out.

  * The driver groups by site and sends one PATCH each, and an unresolvable location code fails
    only its own site.

Stdlib only: no build, no network, no gateway.
"""

import csv
import importlib.util
import pathlib
import unittest

_SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
_DRIVER_PATH = _SCRIPTS / "seed-alpha.py"
_LOCATION_FIXTURES = _SCRIPTS / "fixtures" / "seed" / "alpha" / "location"
_SHOP_FIXTURES = _SCRIPTS / "fixtures" / "seed" / "alpha" / "shop-manager"
_PACK = "location/operating-hours.csv"

_DAYS = ("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")


def _load_driver():
    spec = importlib.util.spec_from_file_location("seed_alpha_location_hours", _DRIVER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


seed_alpha = _load_driver()


def _rows(directory, name):
    with (directory / name).open(newline="") as handle:
        return list(csv.DictReader(handle))


def _minutes(value):
    hour, minute = value.split(":")
    return int(hour) * 60 + int(minute)


class FixtureReferenceTest(unittest.TestCase):
    def setUp(self):
        self.rows = _rows(_LOCATION_FIXTURES, "operating-hours.csv")
        self.sites = {row["code"] for row in _rows(_LOCATION_FIXTURES, "locations.csv")}
        self.shops = {row["locationCode"] for row in _rows(_SHOP_FIXTURES, "shops.csv")}

    def test_everyLocationCodeIsASiteTheLocationPackCreates(self):
        self.assertTrue(self.rows)
        for row in self.rows:
            self.assertIn(row["locationCode"], self.sites, row)

    def test_everyShopHasHoursAndOnlyShopsDo(self):
        # The capacity calendar is a shop-level read: a shop with no hours cannot assemble a day,
        # and hours on the mobile hub or corporate HQ would advertise a day no bay backs.
        self.assertEqual({row["locationCode"] for row in self.rows}, self.shops)

    def test_everyRowCarriesBothTimesInOrder(self):
        for row in self.rows:
            self.assertTrue(row["openTime"], row)
            self.assertTrue(row["closeTime"], row)
            self.assertLess(_minutes(row["openTime"]), _minutes(row["closeTime"]), row)

    def test_everyDayOfWeekIsRecognisedAndNamedAtMostOncePerSite(self):
        seen = set()
        for row in self.rows:
            self.assertIn(row["dayOfWeek"], _DAYS, row)
            key = (row["locationCode"], row["dayOfWeek"])
            self.assertNotIn(key, seen, row)
            seen.add(key)

    def test_sundayIsLeftOutSoItReadsAsClosed(self):
        self.assertNotIn("SUNDAY", {row["dayOfWeek"] for row in self.rows})

    def test_hoursFitTheScheduleBoardsLocationHoursWindow(self):
        # viewSchedule's default LOCATION_HOURS range is 06:00-18:00 local, so a window outside it
        # would be booked capacity the day board cannot show.
        for row in self.rows:
            self.assertGreaterEqual(_minutes(row["openTime"]), _minutes("06:00"), row)
            self.assertLessEqual(_minutes(row["closeTime"]), _minutes("18:00"), row)


class ApiPackClassificationTest(unittest.TestCase):
    def test_thePackIsAnApiPackListedAfterTheLocationsItPatches(self):
        paths = [path for path, _ in seed_alpha.PACK_FILES]
        domains = dict(seed_alpha.PACK_FILES)
        self.assertEqual(domains[_PACK], "@location-hours")
        self.assertIn("@location-hours", seed_alpha.API_PACKS)
        self.assertGreater(paths.index(_PACK), paths.index("location/locations.csv"))


class _StubGateway:
    """Answers the roster read and records every PATCH."""

    def __init__(self, unknown_codes=()):
        self.unknown = set(unknown_codes)
        self.patches = []

    def get(self, path, allow_error=False):
        if path != "/location/locations":
            raise AssertionError(f"unexpected gateway read: {path}")
        codes = [row["code"] for row in _rows(_LOCATION_FIXTURES, "locations.csv")]
        return 200, [{"code": code, "id": f"site-{code}"} for code in codes if code not in self.unknown]

    def patch_json(self, path, body, allow_error=False):
        self.patches.append((path, body))
        return 200, {"id": path.rsplit("/", 1)[-1]}


class HoursAssemblyTest(unittest.TestCase):
    def setUp(self):
        self.rows = _rows(_LOCATION_FIXTURES, "operating-hours.csv")

    def test_onePatchPerSiteCarryingThatSitesWholeWeek(self):
        gateway = _StubGateway()

        ok = seed_alpha.run_location_hours(gateway, _PACK, None)

        self.assertTrue(ok)
        sites = {row["locationCode"] for row in self.rows}
        self.assertEqual(len(gateway.patches), len(sites))
        by_site = {path.rsplit("/", 1)[-1]: body for path, body in gateway.patches}
        self.assertEqual(set(by_site), {f"site-{code}" for code in sites})
        for code in sites:
            expected = [
                {"dayOfWeek": row["dayOfWeek"], "openTime": row["openTime"], "closeTime": row["closeTime"]}
                for row in self.rows
                if row["locationCode"] == code
            ]
            self.assertEqual(by_site[f"site-{code}"], {"operatingHours": expected})

    def test_thePatchCarriesNothingButTheHours(self):
        # PATCH applies every non-null field, so a stray key here would rewrite a site's name,
        # status or timezone on a reseed.
        gateway = _StubGateway()

        seed_alpha.run_location_hours(gateway, _PACK, None)

        for _, body in gateway.patches:
            self.assertEqual(list(body), ["operatingHours"])

    def test_anUnknownLocationFailsOnlyItsOwnSite(self):
        gateway = _StubGateway(unknown_codes={"CLT-NORTH-001"})

        ok = seed_alpha.run_location_hours(gateway, _PACK, None)

        self.assertFalse(ok)
        sites = {row["locationCode"] for row in self.rows}
        self.assertEqual(len(gateway.patches), len(sites) - 1)
        self.assertNotIn("site-CLT-NORTH-001", {path.rsplit("/", 1)[-1] for path, _ in gateway.patches})


if __name__ == "__main__":
    unittest.main()
