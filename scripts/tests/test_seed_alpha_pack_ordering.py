"""Pins the pack ordering and the replication-aware retry in seed-alpha.py.

STAFFING_ASSIGNMENT and PERSON_CREDENTIAL both name a person that people/employees.csv creates, so
they have to run after PERSON. Until #1987 they also lost a race against replication when they ran
too soon after it: pos-people gated an assignment on its ext_people_contact_person replica rather
than on the employee row it owns, and pos-shop-manager refused a skills write outright while the
staffing assignment that creates the mechanic was still in flight.

Both services now answer for themselves -- pos-people from its own employee row, pos-shop-manager
after a bounded wait and with a retryable REPLICATION_PENDING where it genuinely cannot tell -- so
the driver's blind `--settle-seconds` sleep is gone. What replaced it is not a timer: the driver
re-runs a pack only when the owning service reported that *every* failed row was pending
replication, which is a fact about the run rather than a guess about its timing.

Stdlib only: no build, no network, no gateway.
"""

import importlib.util
import json
import pathlib
import unittest

_DRIVER_PATH = pathlib.Path(__file__).resolve().parents[1] / "seed-alpha.py"

# Packs that name a person people/employees.csv created, and the pack that creates them.
_PERSON_DEPENDENT_PACKS = ("STAFFING_ASSIGNMENT", "PERSON_CREDENTIAL")
_PERSON_PACK = "PERSON"


def _load_driver():
    spec = importlib.util.spec_from_file_location("seed_alpha_ordering", _DRIVER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


seed_alpha = _load_driver()


class PackOrderingTest(unittest.TestCase):
    def test_everyPersonDependentPackIsActuallyLoaded(self):
        # A pack named here but absent from PACK_FILES would make the ordering check vacuous.
        loaded = {domain for _, domain in seed_alpha.PACK_FILES}
        for domain in _PERSON_DEPENDENT_PACKS:
            self.assertIn(domain, loaded)

    def test_eachPersonDependentPackRunsAfterPerson(self):
        order = [domain for _, domain in seed_alpha.PACK_FILES]
        person = order.index(_PERSON_PACK)
        for domain in _PERSON_DEPENDENT_PACKS:
            self.assertGreater(
                order.index(domain), person, f"{domain} must load after {_PERSON_PACK}"
            )


class NoBlindSettleWorkaroundTest(unittest.TestCase):
    """#1987 retired the timer-based workaround; a reintroduction should be deliberate."""

    def test_theDriverNoLongerSleepsBlindlyForReplication(self):
        self.assertFalse(hasattr(seed_alpha, "REPLICATION_SENSITIVE_PACKS"))
        self.assertFalse(hasattr(seed_alpha, "loaded_across_attempts"))


class _FakeGateway:
    """Returns one canned audit listing, or raises, in place of the bulk-loader."""

    base_url = "https://gateway.example"

    def __init__(self, records=None, error=None):
        self._records = records
        self._error = error
        self.calls = 0

    def get(self, path):
        self.calls += 1
        if self._error is not None:
            raise self._error
        return 200, self._records


def _failed(code):
    return {
        "reviewStatus": "PENDING",
        "reasonCodes": json.dumps({"errorCode": code, "errorMessage": "..."}),
    }


_APPROVED = {"reviewStatus": "APPROVED", "reasonCodes": None}


class FailuresAreAllReplicationPendingTest(unittest.TestCase):
    """Only a pack the owning service said it could not judge yet is worth sending again."""

    def _classify(self, records=None, error=None):
        return seed_alpha.failures_are_all_replication_pending(
            _FakeGateway(records=records, error=error), "job-1"
        )

    def test_everyFailureIsPendingSoThePackIsRetryable(self):
        self.assertTrue(self._classify([_APPROVED, _failed("REPLICATION_PENDING")]))

    def test_oneRealFailureAmongPendingOnesIsNotRetryable(self):
        # Re-running would just produce the same answer for the row that was genuinely refused,
        # and would hide it behind a second round of output.
        self.assertFalse(
            self._classify([_failed("REPLICATION_PENDING"), _failed("CREDENTIAL_INGEST_REJECTED")])
        )

    def test_aServerFaultIsNotRetryable(self):
        self.assertFalse(self._classify([_failed("INTERNAL_ERROR")]))

    def test_aCleanJobIsNotRetryable(self):
        # Nothing failed, so there is nothing to send again; the caller already treats it as loaded.
        self.assertFalse(self._classify([_APPROVED, _APPROVED]))

    def test_aFailureWithNoReasonIsNotRetryable(self):
        self.assertFalse(self._classify([{"reviewStatus": "PENDING", "reasonCodes": None}]))

    def test_unparseableReasonIsNotRetryable(self):
        self.assertFalse(self._classify([{"reviewStatus": "PENDING", "reasonCodes": "not json"}]))

    def test_anAuditThatCannotBeReadIsNotRetryable(self):
        # The driver must never loop on a pack it cannot classify.
        self.assertFalse(self._classify(error=OSError("gateway down")))


class RetryBoundsTest(unittest.TestCase):
    def test_theRetryIsBounded(self):
        self.assertGreaterEqual(seed_alpha.MAX_REPLICATION_ATTEMPTS, 2)
        self.assertLessEqual(seed_alpha.MAX_REPLICATION_ATTEMPTS, 10)

    def test_theRetryCodeMatchesTheSharedBulkIngestCode(self):
        # pos-bulk-ingest-lib's BulkIngestFailures.RETRYABLE_ERROR_CODE. A rename there without one
        # here would silently turn every retry off.
        self.assertEqual(seed_alpha.REPLICATION_PENDING_CODE, "REPLICATION_PENDING")


if __name__ == "__main__":
    unittest.main()
