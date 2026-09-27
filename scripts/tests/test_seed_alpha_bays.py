"""Pins the maxDutyClass column on the alpha bays fixture (issue #2262).

Spec D13: a bay's duty class is a maximum GVWR class (1-8) only; blank means unconstrained. The BAY
pack is loader-backed (pos-bulk-loader's BayLoaderStrategy, not a python-built request), so
`run_pack_file` in seed-alpha.py uploads bays.csv's raw bytes unchanged as the bulk-load job's
payload -- the fixture file itself is what the seeder sends, column included, and a value the
fixture gets wrong reaches the job exactly as written.

Stdlib only: no build, no network, no gateway.
"""

import csv
import importlib.util
import pathlib
import unittest

_SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
_DRIVER_PATH = _SCRIPTS / "seed-alpha.py"
_BAYS_CSV = _SCRIPTS / "fixtures" / "seed" / "alpha" / "location" / "bays.csv"

# Every bay type the fixture uses, and the maxDutyClass every row of that type must carry -- ""
# for a type with no lift and therefore no ceiling to enforce. Mirrors
# scripts/fixtures/seed/alpha/README.md ("Columns (bays.csv)"), which records the reasoning.
_EXPECTED_MAX_DUTY_CLASS_BY_BAY_TYPE = {
    "HEAVY_DUTY": "8",
    "GENERAL_SERVICE": "3",
    "ALIGNMENT": "4",
    "TIRE_SERVICE": "4",
    "INSPECTION": "5",
    "WASH_DETAIL": "",
}


def _load_driver():
    spec = importlib.util.spec_from_file_location("seed_alpha_bays", _DRIVER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


seed_alpha = _load_driver()


def _rows():
    with open(_BAYS_CSV, newline="") as fh:
        return list(csv.DictReader(fh))


class BaysMaxDutyClassTest(unittest.TestCase):
    def test_everyRowHasAMaxDutyClassColumn(self):
        rows = _rows()
        self.assertTrue(rows, "bays.csv has no data rows")
        for row in rows:
            self.assertIn("maxDutyClass", row, row)

    def test_everyValueIsBlankOrAWholeNumberOneToEight(self):
        for row in _rows():
            value = row["maxDutyClass"]
            if value == "":
                continue
            self.assertTrue(
                value.isdigit(), f"{row['name']} at {row['locationCode']}: {value!r} is not a whole number"
            )
            self.assertTrue(
                1 <= int(value) <= 8, f"{row['name']} at {row['locationCode']}: {value} is out of range 1-8"
            )

    def test_valueIsConsistentPerBayType(self):
        """The rating is a property of the equipment a bay type represents, not of any one row --
        every bay of a given type should carry the same ceiling."""
        by_type = {}
        for row in _rows():
            by_type.setdefault(row["bayType"], set()).add(row["maxDutyClass"])
        for bay_type, values in by_type.items():
            self.assertEqual(
                {_EXPECTED_MAX_DUTY_CLASS_BY_BAY_TYPE[bay_type]},
                values,
                f"{bay_type} bays disagree on maxDutyClass: {values}",
            )

    def test_everyBayTypeInTheFixtureIsAccountedFor(self):
        bay_types = {row["bayType"] for row in _rows()}
        self.assertEqual(bay_types, set(_EXPECTED_MAX_DUTY_CLASS_BY_BAY_TYPE))

    def test_theDriverReadsTheColumnByHeader(self):
        """seed-alpha.py's own read_fixture_rows -- the DictReader path any python-side use of a row
        would go through -- carries the column so it is not dropped by name mismatch."""
        rows = seed_alpha.read_fixture_rows("location/bays.csv")
        heavy = next(r for r in rows if r["bayType"] == "HEAVY_DUTY")
        self.assertEqual(heavy["maxDutyClass"], "8")
        wash = next(r for r in rows if r["bayType"] == "WASH_DETAIL")
        self.assertEqual(wash["maxDutyClass"], "")

    def test_theUploadedPayloadCarriesTheColumnVerbatim(self):
        """run_pack_file posts the fixture file's raw bytes as the bulk-load job's payload for a
        loader-backed pack like BAY -- this reads exactly what it uploads."""
        with open(_BAYS_CSV, "rb") as fh:
            payload = fh.read()
        header = payload.decode("utf-8").splitlines()[0].split(",")
        self.assertIn("maxDutyClass", header)
        self.assertIn(b",HEAVY_DUTY,1,8\n", payload)
        self.assertIn(b",WASH_DETAIL,1,\n", payload)


if __name__ == "__main__":
    unittest.main()
