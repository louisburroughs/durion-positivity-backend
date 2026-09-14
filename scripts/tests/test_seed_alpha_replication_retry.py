"""Pins seed-alpha.py's retry of packs that lose a race against replication.

STAFFING_ASSIGNMENT and MECHANIC_SKILL both resolve a person that people/employees.csv created
moments earlier. When the seed runs fast the owning service's replica has not caught up and every
row is refused with 404 -- "Person not found", "Mechanic not found for person". On alpha those
three packs ran within eight seconds of each other and both dependents reported 0 successes out of
39 and 23; the same packs passed on a slower run, which is what identifies this as a race rather
than a data fault.

The retry is safe for exactly these two because both refuse duplicates: a row that did land is
rejected on the second attempt rather than written twice. That property is what the membership test
below guards -- customer/*.csv was not idempotent until #1978, and retrying it would have doubled
every customer.

Stdlib only: no build, no network, no gateway.
"""

import importlib.util
import pathlib
import unittest

_DRIVER_PATH = pathlib.Path(__file__).resolve().parents[1] / "seed-alpha.py"


def _load_driver():
    spec = importlib.util.spec_from_file_location("seed_alpha_retry", _DRIVER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


seed_alpha = _load_driver()


class ReplicationSensitivePacksTest(unittest.TestCase):
    def test_theTwoPacksThatLoseTheRaceAreListed(self):
        self.assertEqual(
            seed_alpha.REPLICATION_SENSITIVE_PACKS,
            {"STAFFING_ASSIGNMENT", "MECHANIC_SKILL"},
        )

    def test_customerPacksAreNotRetried(self):
        # They were not idempotent until #1978; a retry would duplicate every party, which is the
        # failure that broke owner resolution for all 260 commercial-owned vehicles.
        self.assertNotIn("CUSTOMER", seed_alpha.REPLICATION_SENSITIVE_PACKS)
        self.assertNotIn("COMMERCIAL_CUSTOMER", seed_alpha.REPLICATION_SENSITIVE_PACKS)

    def test_everyRetriedPackIsActuallyLoaded(self):
        # A pack named here but absent from PACK_FILES would be a silent no-op.
        loaded = {domain for _, domain in seed_alpha.PACK_FILES}
        self.assertTrue(seed_alpha.REPLICATION_SENSITIVE_PACKS.issubset(loaded))

    def test_eachRetriedPackRunsAfterThePackItDependsOn(self):
        # The retry only helps if the dependency was already attempted; if PERSON ran afterwards the
        # ordering itself would be the bug.
        order = [domain for _, domain in seed_alpha.PACK_FILES]
        person = order.index("PERSON")
        for domain in seed_alpha.REPLICATION_SENSITIVE_PACKS:
            self.assertGreater(
                order.index(domain), person, f"{domain} must load after PERSON"
            )


if __name__ == "__main__":
    unittest.main()
