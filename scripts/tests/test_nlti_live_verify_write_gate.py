"""Offline unit tests for the #1397 write-gate telemetry checks in nlti_live_verify.py (#1408).

The `wg-telemetry-outcome` check and the harvester method behind it are the parts of the harness
that only ever execute against a live alpha stack, so a defect in them surfaces as a confusing
result mid-verification-run — after a deploy, with no fast way to tell a harness bug from a real
product regression. These tests pin that logic with fakes: no sockets, no Loki, no alpha.

Run: python3 -m unittest test_nlti_live_verify_write_gate -q
"""

import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = REPO_ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import nlti_live_verify as verify  # noqa: E402

CORRELATION = "00000000-0000-7000-8000-0000000000aa"
OTHER_CORRELATION = "00000000-0000-7000-8000-0000000000bb"


def telemetry_record(correlation, outcome=None, is_write=True, ts_ns=0):
    """A harvested nlti.request.telemetry record, shaped like the emitted JSON."""
    write = {"isWrite": is_write}
    if outcome is not None:
        write["confirmationOutcome"] = outcome
    return {
        "eventType": verify.TELEMETRY_EVENT_TYPE,
        "correlationId": correlation,
        "write": write,
        "_lokiTimestampNs": ts_ns,
    }


class FakeLoki:
    """Stands in for the Loki client: returns a canned record set, records the queries made."""

    def __init__(self, records):
        self.records = records
        self.calls = []

    def events(self, event_type, start, end, extra_filter=None):
        self.calls.append((event_type, extra_filter))
        return list(self.records)


class FakeArgs:
    telemetry_wait_seconds = 0.0
    telemetry_poll_seconds = 0.0
    telemetry_window_pad_seconds = 0.0
    verbose = False


class FakeResponse:
    def __init__(self):
        self.started = datetime.now(timezone.utc) - timedelta(seconds=5)
        self.ended = datetime.now(timezone.utc)


class FakeCtx:
    def __init__(self, harvester):
        self.harvester = harvester


def harvester_for(records):
    return verify.TelemetryHarvester(FakeLoki(records), FakeArgs())


# _poll pads a real time window around these, so they must be datetimes, not sentinels.
WINDOW_START = datetime.now(timezone.utc) - timedelta(seconds=5)
WINDOW_END = datetime.now(timezone.utc)


def lookup(records, correlation=CORRELATION):
    return harvester_for(records).confirmation_outcome_by_correlation(
        correlation, WINDOW_START, WINDOW_END)


def suite():
    return verify.SuiteResult("write-gate", "6", "#1408", "test suite")


def check(result, check_id):
    return next(c for c in result.checks if c.id == check_id)


class ConfirmationOutcomeLookupTest(unittest.TestCase):
    """TelemetryHarvester.confirmation_outcome_by_correlation."""

    def test_selects_the_leg_carrying_an_outcome_over_the_submit_leg(self):
        # Submit and confirm share one correlation id — confirm reuses the request's — so both
        # legs come back from the same query. by_correlation() would take whichever Loki listed
        # first; this must take the one with the outcome.
        submit = telemetry_record(CORRELATION, outcome=None, ts_ns=100)
        confirm = telemetry_record(CORRELATION, outcome="confirmed", ts_ns=200)
        found = lookup([submit, confirm])

        self.assertIsNotNone(found)
        self.assertEqual(verify.tel_confirmation_outcome(found), "confirmed")

    def test_submit_leg_ordered_first_does_not_win(self):
        # Same as above with the list order reversed, pinning that selection is by content and
        # not by Loki's return order.
        confirm = telemetry_record(CORRELATION, outcome="confirmed", ts_ns=200)
        submit = telemetry_record(CORRELATION, outcome=None, ts_ns=100)
        found = lookup([confirm, submit])

        self.assertEqual(verify.tel_confirmation_outcome(found), "confirmed")

    def test_latest_outcome_wins_when_a_plan_was_superseded_first(self):
        # A plan superseded during submit, then a confirm on the replacement: both carry an
        # outcome under the same correlation id, and the terminal one is the later.
        superseded = telemetry_record(CORRELATION, outcome="superseded", ts_ns=100)
        confirmed = telemetry_record(CORRELATION, outcome="confirmed", ts_ns=300)
        found = lookup([confirmed, superseded])

        self.assertEqual(verify.tel_confirmation_outcome(found), "confirmed")

    def test_ignores_records_from_another_correlation_id(self):
        foreign = telemetry_record(OTHER_CORRELATION, outcome="cancelled", ts_ns=400)
        found = lookup([foreign])

        self.assertIsNone(found)

    def test_returns_none_when_only_the_submit_leg_landed(self):
        submit = telemetry_record(CORRELATION, outcome=None, ts_ns=100)
        found = lookup([submit])

        self.assertIsNone(found)

    def test_marks_the_record_as_joined_by_correlation_id(self):
        # The join marker is what keeps a result out of UNVERIFIED-ATTRIBUTION territory.
        confirm = telemetry_record(CORRELATION, outcome="cancelled", ts_ns=200)
        found = lookup([confirm])

        self.assertEqual(found["_join"], "correlationId")


class ExpectConfirmationOutcomeTest(unittest.TestCase):
    """expect_confirmation_outcome — the wg-telemetry-outcome check itself."""

    def run_check(self, records, expected):
        result = suite()
        verify.expect_confirmation_outcome(
            FakeCtx(harvester_for(records)), result, CORRELATION, FakeResponse(), expected)
        return check(result, "wg-telemetry-outcome")

    def test_passes_when_the_outcome_matches(self):
        entry = self.run_check([telemetry_record(CORRELATION, outcome="confirmed")], "confirmed")

        self.assertEqual(entry.status, verify.PASS)
        self.assertIn("confirmed", entry.evidence)

    def test_passes_for_the_read_only_cancellation_leg(self):
        # A cancel is a terminal outcome too, which is what lets this criterion be verified
        # without --allow-writes.
        entry = self.run_check([telemetry_record(CORRELATION, outcome="cancelled")], "cancelled")

        self.assertEqual(entry.status, verify.PASS)

    def test_fails_when_the_outcome_is_not_the_expected_one(self):
        entry = self.run_check([telemetry_record(CORRELATION, outcome="expired")], "confirmed")

        self.assertEqual(entry.status, verify.FAIL)
        # The evidence must name both sides — a bare FAIL cannot be triaged from the report.
        self.assertIn("expired", entry.evidence)
        self.assertIn("confirmed", entry.evidence)

    def test_fails_rather_than_skips_when_no_outcome_record_landed(self):
        # #1397 shipped the emission, so an absent record is a real failure now, not the
        # documented product gap it used to be. A SKIP here would hide a regression.
        entry = self.run_check([telemetry_record(CORRELATION, outcome=None)], "confirmed")

        self.assertEqual(entry.status, verify.FAIL)
        self.assertIn("confirmationOutcome", entry.evidence)


if __name__ == "__main__":
    unittest.main()
