"""Pins the LOCATION pack's skip of the row the driver already created.

`--bootstrap-location` creates one site up front, because a bulk job must be scoped to a location
that exists and a freshly reset database has none. The LOCATION pack then carried that same site
again, and the job reported one failure for a row the driver had itself just loaded. The driver
printed "expected duplicate failure" to explain it away — a failure the operator is told to ignore,
in the same column as failures that must not be ignored. A clean run should report a clean load.

Stdlib only: no build, no network, no gateway.
"""

import importlib.util
import pathlib
import unittest

_DRIVER_PATH = pathlib.Path(__file__).resolve().parents[1] / "seed-alpha.py"


def _load_driver():
    spec = importlib.util.spec_from_file_location("seed_alpha_bootstrap", _DRIVER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


seed_alpha = _load_driver()

CSV = (
    b"name,code,city,active\n"
    b"Charlotte Main,CLT-MAIN-001,Charlotte,true\n"
    b"Charlotte North,CLT-NORTH-001,Charlotte,true\n"
    b"Charlotte South,CLT-SOUTH-001,Charlotte,true\n"
)


class WithoutRowCodedTest(unittest.TestCase):
    def _rows(self, blob):
        return blob.decode("utf-8").strip().splitlines()

    def test_theBootstrappedRowIsDropped(self):
        out = self._rows(seed_alpha._without_row_coded(CSV, "CLT-MAIN-001"))
        self.assertEqual(len(out), 3, "header plus the two rows the driver did not create")
        self.assertNotIn("CLT-MAIN-001", "\n".join(out))

    def test_theHeaderSurvives(self):
        # Dropping the header would make the upload unparseable rather than merely short.
        out = self._rows(seed_alpha._without_row_coded(CSV, "CLT-MAIN-001"))
        self.assertEqual(out[0], "name,code,city,active")

    def test_theOtherRowsAreUntouched(self):
        out = self._rows(seed_alpha._without_row_coded(CSV, "CLT-MAIN-001"))
        self.assertIn("Charlotte North,CLT-NORTH-001,Charlotte,true", out)
        self.assertIn("Charlotte South,CLT-SOUTH-001,Charlotte,true", out)

    def test_anUnknownCodeDropsNothing(self):
        # A code that is not in the file must not quietly shrink the load.
        out = self._rows(seed_alpha._without_row_coded(CSV, "ZZZ-NOT-HERE"))
        self.assertEqual(len(out), 4)

    def test_aFileWithoutACodeColumnIsReturnedUnchanged(self):
        # Better to send the file as-is than to guess which column is the key.
        other = b"name,city\nCharlotte Main,Charlotte\n"
        self.assertEqual(seed_alpha._without_row_coded(other, "CLT-MAIN-001"), other)

    def test_aQuotedCommaInAnEarlierFieldDoesNotShiftTheCodeColumn(self):
        # split(",") reads "Service Center" as the whole name and "West" as the code, so the
        # bootstrapped row matches nothing and is sent again — the exact failure this filter exists
        # to prevent, on a site name that is perfectly legal CSV.
        quoted = (
            b'name,code,city,active\n'
            b'"Service Center, West",CLT-MAIN-001,Charlotte,true\n'
            b'Charlotte North,CLT-NORTH-001,Charlotte,true\n'
        )
        out = seed_alpha._without_row_coded(quoted, "CLT-MAIN-001")
        text = out.decode("utf-8")
        self.assertNotIn("CLT-MAIN-001", text)
        self.assertIn("CLT-NORTH-001", text)

    def test_aQuotedCommaSurvivesInARowThatIsKept(self):
        # The kept row must still parse as one field, not two.
        quoted = (
            b'name,code,city,active\n'
            b'"Service Center, West",CLT-NORTH-001,Charlotte,true\n'
        )
        out = seed_alpha._without_row_coded(quoted, "CLT-MAIN-001")
        import csv as _csv
        import io as _io

        rows = list(_csv.reader(_io.StringIO(out.decode("utf-8"))))
        self.assertEqual(rows[1][0], "Service Center, West")
        self.assertEqual(rows[1][1], "CLT-NORTH-001")

    def test_aPartialRowDoesNotCrashTheFilter(self):
        ragged = b"name,code,city,active\nOnly Name\nCharlotte Main,CLT-MAIN-001,Charlotte,true\n"
        out = self._rows(seed_alpha._without_row_coded(ragged, "CLT-MAIN-001"))
        self.assertIn("Only Name", out)
        self.assertNotIn("CLT-MAIN-001", "\n".join(out))


if __name__ == "__main__":
    unittest.main()
