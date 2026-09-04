import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts import analytics_gate_run as runner


def expected_answer(**overrides):
    expected = {
        "outcome": "answered-correctly",
        "intent": "ANALYTICS",
        "tier": "COMPLEX",
        "tool_sequence": ["getRevenue", "getAgedReceivables"],
        "tool_calls": [
            {
                "name": "getRevenue",
                "arguments": {"startDate": "2026-08-01"},
                "arguments_match": "partial",
            },
            {
                "name": "getAgedReceivables",
                "arguments": {"asOfDate": "2026-09-01", "minimumDaysPastDue": 60},
                "arguments_match": "exact",
            },
        ],
        "numbers": [{"value": 125.0, "absolute_tolerance": 0.5}],
        "id_set": [
            "11111111-1111-1111-1111-111111111111",
            "22222222-2222-2222-2222-222222222222",
        ],
        "id_order": [
            "22222222-2222-2222-2222-222222222222",
            "11111111-1111-1111-1111-111111111111",
        ],
        "required_strings": ["Revenue"],
        "forbidden_strings": ["estimated"],
    }
    expected.update(overrides)
    return expected


def replay_observation(**overrides):
    observation = {
        "intent": "ANALYTICS",
        "modelTier": "COMPLEX",
        "toolCalls": [
            {
                "sequence": 1,
                "name": "getRevenue",
                "arguments": json.dumps(
                    {"startDate": "2026-08-01", "endDate": "2026-08-31"}
                ),
                "result": "{}",
                "error": None,
            },
            {
                "sequence": 2,
                "name": "getAgedReceivables",
                "arguments": json.dumps(
                    {"asOfDate": "2026-09-01", "minimumDaysPastDue": 60}
                ),
                "result": "{}",
                "error": None,
            },
        ],
        "finalResponse": (
            "Revenue: 125.4. Customers: "
            "22222222-2222-2222-2222-222222222222, "
            "11111111-1111-1111-1111-111111111111."
        ),
        "error": None,
    }
    observation.update(overrides)
    return observation


def question(expected=None):
    return {
        "fixture_id": "q01",
        "expected_section": "Q1",
        "ground_truth_sql": "ground-truth/q01.sql",
        "utterance": "Question?",
        "in_chat_path_gate": True,
        "window": {"shape": "calendar", "resolved_range": "2026-08-01..2026-08-31"},
        "expected_plan": {"min_tool_calls": {"getRevenue": 1, "getAgedReceivables": 1}},
        "expected": expected or expected_answer(),
    }


class ArgumentAccuracyTest(unittest.TestCase):
    def test_partial_json_arguments_allow_nested_extra_values(self):
        expected_calls = [
            {
                "name": "tool",
                "arguments": {"filter": {"status": "OPEN"}},
                "arguments_match": "partial",
            }
        ]
        observed_calls = [
            {
                "name": "tool",
                "arguments": {"filter": {"status": "OPEN", "limit": 20}, "page": 0},
            }
        ]

        result = runner.grade_argument_accuracy(expected_calls, observed_calls)

        self.assertTrue(result["passed"], result["details"])

    def test_exact_json_arguments_reject_extra_values(self):
        expected_calls = [
            {"name": "tool", "arguments": {"status": "OPEN"}, "arguments_match": "exact"}
        ]
        observed_calls = [
            {"name": "tool", "arguments": {"status": "OPEN", "limit": 20}}
        ]

        result = runner.grade_argument_accuracy(expected_calls, observed_calls)

        self.assertFalse(result["passed"])
        self.assertIn("unexpected", result["details"])


class ToolPlanTest(unittest.TestCase):
    def test_expected_plan_counts_are_derived_from_replay_calls(self):
        calls = [
            {"name": "searchWorkorders", "arguments": {}},
            {"name": "searchWorkorders", "arguments": {}},
            {"name": "getAgedReceivables", "arguments": {}},
        ]

        result = runner.check_expected_plan(
            {"min_tool_calls": {"searchWorkorders": 2, "getAgedReceivables": 1}}, calls
        )

        self.assertTrue(result.startswith("OK"), result)

    def test_tool_selection_ignores_order_but_sequence_does_not(self):
        expected = {"tool_sequence": ["first", "second"]}
        calls = [{"name": "second"}, {"name": "first"}]

        selection = runner.grade_tool_selection(expected, calls)
        sequence = runner.grade_tool_call_sequence(expected, calls)

        self.assertTrue(selection["passed"], selection["details"])
        self.assertFalse(sequence["passed"])
        self.assertIn("expected ['first', 'second']", sequence["details"])

    def test_explicit_empty_tool_sequence_rejects_unexpected_calls(self):
        expected = {"tool_sequence": []}
        calls = [{"name": "unexpected"}]

        selection = runner.grade_tool_selection(expected, calls)
        sequence = runner.grade_tool_call_sequence(expected, calls)

        self.assertFalse(selection["passed"])
        self.assertFalse(sequence["passed"])


class AggregationTest(unittest.TestCase):
    def test_number_uses_absolute_tolerance(self):
        result = runner.grade_aggregation(
            {"numbers": [{"value": 125.0, "absolute_tolerance": 0.5}]},
            "The total is 125.4.",
        )

        self.assertTrue(result["passed"], result["details"])

    def test_number_outside_absolute_tolerance_fails(self):
        result = runner.grade_aggregation(
            {"numbers": [{"value": 125.0, "absolute_tolerance": 0.1}]},
            "The total is 125.4.",
        )

        self.assertFalse(result["passed"])
        self.assertIn("125.0", result["details"])

    def test_uuid_set_is_exact_and_uuid_order_is_checked(self):
        first = "11111111-1111-1111-1111-111111111111"
        second = "22222222-2222-2222-2222-222222222222"
        unexpected = "33333333-3333-3333-3333-333333333333"

        wrong_set = runner.grade_aggregation(
            {"id_set": [first, second]}, f"Customers: {first}, {second}, {unexpected}"
        )
        wrong_order = runner.grade_aggregation(
            {"id_order": [first, second]}, f"Customers: {second}, then {first}"
        )

        self.assertFalse(wrong_set["passed"])
        self.assertIn("unexpected ids", wrong_set["details"])
        self.assertFalse(wrong_order["passed"])
        self.assertIn("ordering", wrong_order["details"])


class OutcomeTest(unittest.TestCase):
    def test_all_supported_outcomes_are_emitted(self):
        cases = {
            "answered-correctly": ("Here is the answer.", None),
            "asked-appropriately": ("Which metric do you mean?", None),
            "declined-appropriately": ("I cannot access that safely.", None),
            "failed": ("", "model unavailable"),
        }
        for expected_outcome, (answer, error) in cases.items():
            with self.subTest(expected_outcome=expected_outcome):
                expected = {"outcome": expected_outcome}
                if expected_outcome == "declined-appropriately":
                    expected["required_strings"] = ["cannot"]
                result = runner.grade_fixture(
                    question(expected),
                    replay_observation(toolCalls=[], finalResponse=answer, error=error),
                )
                self.assertEqual(expected_outcome, result["outcome"])
                self.assertEqual("PASS", result["verdict"])

    def test_failed_axis_makes_answered_fixture_fail(self):
        result = runner.grade_fixture(
            question(expected_answer(intent="REPORTING")), replay_observation()
        )

        self.assertEqual("failed", result["outcome"])
        self.assertEqual("FAIL", result["verdict"])
        self.assertFalse(result["axes"]["intent"]["passed"])


class ReplayReportTest(unittest.TestCase):
    def write_report(self, directory, payload):
        path = Path(directory) / "report.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    def test_report_maps_camel_case_trace_entries_by_fixture_id(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_report(
                directory, {"results": [{"fixture_id": "q01", **replay_observation()}]}
            )

            report = runner.load_replay_report(path, {"q01"})

        self.assertEqual("COMPLEX", report["q01"]["tier"])
        self.assertEqual("getRevenue", report["q01"]["tool_calls"][0]["name"])
        self.assertIsInstance(report["q01"]["tool_calls"][0]["arguments"], dict)

    def test_malformed_report_has_clear_fixture_specific_error(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_report(
                directory,
                {
                    "results": [
                        {
                            "fixture_id": "q01",
                            **replay_observation(
                                toolCalls=[{"name": "tool", "arguments": "not-json"}]
                            ),
                        }
                    ]
                },
            )

            with self.assertRaisesRegex(ValueError, "q01.*arguments.*valid JSON"):
                runner.load_replay_report(path, {"q01"})

    def test_unknown_report_fixture_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_report(
                directory, {"results": [{"fixture_id": "q99", **replay_observation()}]}
            )

            with self.assertRaisesRegex(ValueError, "unknown fixture_id.*q99"):
                runner.load_replay_report(path, {"q01"})

    def test_malformed_expected_block_is_rejected(self):
        malformed = expected_answer(numbers=[{"value": 10, "absolute_tolerance": -1}])

        with self.assertRaisesRegex(ValueError, "q01.*absolute_tolerance"):
            runner.grade_fixture(question(malformed), replay_observation())


class CliModeTest(unittest.TestCase):
    def document(self):
        return {"eval_as_of": "2026-09-01", "questions": [question()]}

    @mock.patch.object(runner, "questions_provenance")
    @mock.patch.object(runner, "ask")
    def test_replay_mode_needs_no_token_and_never_calls_http(self, ask, provenance):
        provenance.return_value = {
            "path": "questions.json",
            "blob_sha": "abc",
            "commit": "def",
            "uncommitted": False,
        }
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict(
            os.environ, {}, clear=True
        ):
            directory = Path(directory)
            questions_path = directory / "questions.json"
            questions_path.write_text(json.dumps(self.document()), encoding="utf-8")
            report_path = directory / "report.json"
            report_path.write_text(
                json.dumps({"results": [{"fixture_id": "q01", **replay_observation()}]}),
                encoding="utf-8",
            )
            out_dir = directory / "out"

            runner.main(
                [
                    "--out",
                    str(out_dir),
                    "--questions",
                    str(questions_path),
                    "--replay-report",
                    str(report_path),
                ]
            )

            record = json.loads((out_dir / "run.json").read_text(encoding="utf-8"))
            markdown = (out_dir / "run.md").read_text(encoding="utf-8")

        ask.assert_not_called()
        self.assertEqual("PASS", record["results"][0]["verdict"])
        self.assertIn("argument_accuracy", record["results"][0]["axes"])
        self.assertIn("Overall verdict: **PASS**", markdown)
        self.assertIn("getRevenue", markdown)
        self.assertNotIn("always reads n/a", markdown)

    @mock.patch.object(runner, "questions_provenance")
    @mock.patch.object(runner, "ask")
    def test_live_mode_preserves_http_path_and_unobserved_calls(self, ask, provenance):
        provenance.return_value = {
            "path": "questions.json",
            "blob_sha": "abc",
            "commit": "def",
            "uncommitted": False,
        }
        ask.return_value = {"answer": "live answer", "error": None, "elapsed_s": 1.0}
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict(
            os.environ, {}, clear=True
        ):
            directory = Path(directory)
            questions_path = directory / "questions.json"
            questions_path.write_text(json.dumps(self.document()), encoding="utf-8")
            out_dir = directory / "out"

            runner.main(
                [
                    "--out",
                    str(out_dir),
                    "--questions",
                    str(questions_path),
                    "--token",
                    _token({"sub": "admin.alpha", "roles": ["ROLE_ADMIN"], "perm_bits": "test-token"}),
                ]
            )

            record = json.loads((out_dir / "run.json").read_text(encoding="utf-8"))
            markdown = (out_dir / "run.md").read_text(encoding="utf-8")

        ask.assert_called_once()
        self.assertIsNone(record["results"][0]["tool_calls"])
        # The bearer token must not survive into the record; only its decoded, non-secret claims.
        self.assertNotIn("test-token", json.dumps(record))
        self.assertEqual(record["actor"]["subject"], "admin.alpha")
        self.assertEqual(record["actor"]["effective_roles"], ["ROLE_ADMIN"])
        self.assertNotIn("void", record)
        self.assertIn("n/a (not exposed by the endpoint)", markdown)


class ExistingHelperBehaviorTest(unittest.TestCase):
    def test_select_and_live_plan_fallback_are_preserved(self):
        questions = [
            {"fixture_id": "q01", "in_chat_path_gate": True},
            {"fixture_id": "q02", "in_chat_path_gate": False},
        ]

        self.assertEqual([questions[0]], runner.select(questions, None, False))
        self.assertEqual(questions, runner.select(questions, None, True))
        self.assertIn(
            "not checked",
            runner.check_expected_plan({"min_tool_calls": {"tool": 1}}, None),
        )
        self.assertEqual("n/a (not exposed by the endpoint)", runner.format_tool_calls(None))

def _token(claims):
    """A JWT-shaped string carrying `claims`. Signature is irrelevant — the runner only decodes."""
    import base64

    def seg(obj):
        raw = json.dumps(obj).encode()
        return base64.urlsafe_b64encode(raw).decode().rstrip("=")

    return f"{seg({'alg': 'none'})}.{seg(claims)}.signature"


class ActorProvenanceTest(unittest.TestCase):
    """#1706: a run whose actor is unrecorded cannot be compared with any other run."""

    def test_records_subject_and_effective_roles(self):
        actor = runner.actor_provenance(
            _token({"sub": "admin.alpha", "roles": ["ROLE_ADMIN", "ROLE_FACTOR_PASSWORD"], "perm_bits": "abc"})
        )

        self.assertEqual(actor["subject"], "admin.alpha")
        self.assertEqual(actor["roles"], ["ROLE_ADMIN", "ROLE_FACTOR_PASSWORD"])
        # The factor marker says nothing about what the caller may reach, so it must not be
        # mistaken for the role the gate checks against.
        self.assertEqual(actor["effective_roles"], ["ROLE_ADMIN"])
        self.assertEqual(actor["permission_bits_length"], 3)

    def test_never_returns_the_token_or_its_signature(self):
        token = _token({"sub": "admin.alpha", "roles": ["ROLE_ADMIN"], "perm_bits": "s3cr3t-bits"})
        actor = runner.actor_provenance(token)

        # The record is written to disk and pasted into issues; the credential must not ride along.
        blob = json.dumps(actor)
        self.assertNotIn(token, blob)
        self.assertNotIn("signature", blob)
        self.assertNotIn("s3cr3t-bits", blob)
        self.assertEqual(actor["permission_bits_length"], len("s3cr3t-bits"))

    def test_malformed_token_records_an_error_rather_than_raising(self):
        actor = runner.actor_provenance("not-a-jwt")

        self.assertIn("error", actor)
        self.assertNotIn("subject", actor)

    def test_role_claim_may_be_singular_or_a_bare_string(self):
        self.assertEqual(runner.actor_provenance(_token({"role": "ROLE_ADMIN"}))["effective_roles"], ["ROLE_ADMIN"])
        self.assertEqual(
            runner.actor_provenance(_token({"role": ["ROLE_CONTROLLER"]}))["effective_roles"], ["ROLE_CONTROLLER"]
        )

    def test_undecodable_token_is_reported_as_such_not_as_a_role_mismatch(self):
        # "carries roles []" would send the reader to check an actor's permissions when the real
        # problem is that the token never parsed. Different cause, different message.
        with self.assertRaises(SystemExit) as caught:
            runner.main(["--out", tempfile.mkdtemp(), "--token", "not-a-jwt", "--only", "q01"])

        message = str(caught.exception)
        self.assertIn("could not establish the actor", message)
        self.assertNotIn("carries roles", message)
        self.assertIn("could not be read", message)

    def test_abort_message_never_contains_the_token(self):
        secret = "not-a-jwt-but-still-a-credential"
        with self.assertRaises(SystemExit) as caught:
            runner.main(["--out", tempfile.mkdtemp(), "--token", secret, "--only", "q01"])

        self.assertNotIn(secret, str(caught.exception))

    def test_wrong_role_names_the_actor_and_the_expected_role(self):
        token = _token({"sub": "margaret.olsen", "roles": ["ROLE_CONTROLLER", "ROLE_FACTOR_PASSWORD"]})
        with self.assertRaises(SystemExit) as caught:
            runner.main(["--out", tempfile.mkdtemp(), "--token", token, "--only", "q01"])

        message = str(caught.exception)
        self.assertIn("margaret.olsen", message)
        self.assertIn("ROLE_CONTROLLER", message)
        self.assertIn("ROLE_ADMIN", message)
        self.assertNotIn(token, message)

    def test_expected_role_default_is_the_documented_gate_actor(self):
        # Pinned deliberately: the corpus README names admin.alpha and explains why no role-scoped
        # actor covers workorder + A/R + A/P. Changing one without the other reopens #1706.
        self.assertEqual(runner.EXPECTED_ROLE_DEFAULT, "ROLE_ADMIN")


if __name__ == "__main__":
    unittest.main()
