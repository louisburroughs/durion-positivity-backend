"""Tests for scripts/derive_tagging_hard_negatives.py: the hard-negative rule and the fixture
round-trip. No Java: the heuristic's answers are given, as --answers would read them. Stdlib only.

    python3 -m unittest scripts.test_derive_tagging_hard_negatives
"""

import contextlib
import io
import json
import os
import pathlib
import tempfile
import unittest

from scripts import derive_tagging_hard_negatives as derive

NEGATIVE = {
    "follows_previous_turn": False, "simple_chat": False, "needs_web_search": False, "about_inventory": False,
    "about_orders": False, "implies_date_window": False, "admin_account_question": False,
    "compound_question": False, "workflow_state": "IDLE", "intent": "QUERY", "complexity": "SINGLE_LOOKUP",
    "risk": "LOW", "domain": "master", "entity": [],
}


def answers(**positive):
    out = {tag: "false" for tag in derive.HARD_NEGATIVE_TAGS}
    out["workflow_state"] = "IDLE"
    out.update({"intent": "UNKNOWN", "risk": "HIGH", "complexity": "MULTI_DOMAIN", "domain": "master"})
    out.update(positive)
    return out


class HardNegativeRuleTest(unittest.TestCase):
    def test_negative_answered_positive_is_hard(self):
        self.assertEqual(derive.hard_negatives(NEGATIVE, answers(about_inventory="true")), ["about_inventory"])

    def test_positive_expected_is_never_hard(self):
        expected = dict(NEGATIVE, about_inventory=True)
        self.assertEqual(derive.hard_negatives(expected, answers(about_inventory="true")), [])

    def test_workflow_state_non_idle_on_idle(self):
        self.assertEqual(derive.hard_negatives(NEGATIVE, answers(workflow_state="RECEIVING_ASN")), ["workflow_state"])
        expected = dict(NEGATIVE, workflow_state="RECEIVING_ASN")
        self.assertEqual(derive.hard_negatives(expected, answers(workflow_state="CREATING_PO")), [])

    def test_order_is_nouls_then_workflow_state(self):
        got = derive.hard_negatives(NEGATIVE, answers(
            workflow_state="CREATING_PO", compound_question="true", follows_previous_turn="true"))
        self.assertEqual(got, ["follows_previous_turn", "compound_question", "workflow_state"])

    def test_router_tags_and_entities_never_listed(self):
        # the heuristic's safe_default answers (UNKNOWN / HIGH / MULTI_DOMAIN) are not hard negatives
        self.assertEqual(derive.hard_negatives(NEGATIVE, answers()), [])

    def test_missing_answer_is_not_positive(self):
        self.assertEqual(derive.hard_negatives(NEGATIVE, {}), [])


class DeriveTest(unittest.TestCase):
    def fixtures(self, hard):
        utterance = {"id": "en-0001", "text": "Is the store open?", "source": "new",
                     "expected_tags": dict(NEGATIVE), "hard_negative_for": hard, "notes": ""}
        return {"en": (None, {"utterances": [utterance]})}

    def test_derive_updates_and_reports(self):
        fixtures = self.fixtures([])
        changes = derive.derive(fixtures, {"Is the store open?": answers(about_inventory="true")})
        self.assertEqual(changes, {"en": [("en-0001", [], ["about_inventory"])]})
        self.assertEqual(fixtures["en"][1]["utterances"][0]["hard_negative_for"], ["about_inventory"])

    def test_derive_is_idempotent(self):
        fixtures = self.fixtures(["about_inventory"])
        self.assertEqual(derive.derive(fixtures, {"Is the store open?": answers(about_inventory="true")}), {})

    def test_derive_refuses_a_text_without_answers(self):
        with self.assertRaises(SystemExit):
            derive.derive(self.fixtures([]), {})

    def test_parse_answers_reads_the_java_output(self):
        line = json.dumps({"text": "hello", "answers": {"simple_chat": "true"}})
        self.assertEqual(derive.parse_answers(["Picked up JAVA_TOOL_OPTIONS: ...", line]),
                         {"hello": {"simple_chat": "true"}})

    def _gate_copy(self):
        """A temporary copy of the fixtures, so no test can rewrite the real ones."""
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        for language, (path, _) in derive.load_fixtures().items():
            with open(os.path.join(tmp.name, path.name), "wb") as handle:
                handle.write(path.read_bytes())
        return tmp.name

    def _answers_file(self, gate_dir, flagged_text):
        fixtures = derive.load_fixtures(pathlib.Path(gate_dir))
        rows = [{"text": u["text"], "answers": answers(about_inventory="true") if u["text"] == flagged_text
                 else answers()} for _, f in fixtures.values() for u in f["utterances"]]
        path = os.path.join(gate_dir, "answers.jsonl")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n")
        return path

    def test_check_mode_writes_nothing_and_flags_drift(self):
        gate = self._gate_copy()
        first = derive.load_fixtures(pathlib.Path(gate))["en"][1]["utterances"][0]
        answers_path = self._answers_file(gate, first["text"])
        before = {name: pathlib.Path(gate, name).read_bytes() for name in ("en.json", "fr-CA.json", "es.json")}
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(derive.main(["--answers", answers_path, "--check", "--gate-dir", gate]), 1)
        self.assertIn(f"{first['id']}: ", out.getvalue())
        self.assertEqual({name: pathlib.Path(gate, name).read_bytes() for name in before}, before)

    def test_write_mode_rewrites_only_the_derived_lists(self):
        gate = self._gate_copy()
        first = derive.load_fixtures(pathlib.Path(gate))["en"][1]["utterances"][0]
        answers_path = self._answers_file(gate, first["text"])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(derive.main(["--answers", answers_path, "--gate-dir", gate]), 0)
        rewritten = derive.load_fixtures(pathlib.Path(gate))["en"][1]["utterances"][0]
        expected = derive.hard_negatives(first["expected_tags"], answers(about_inventory="true"))
        self.assertEqual(rewritten["hard_negative_for"], expected)
        self.assertEqual({k: v for k, v in rewritten.items() if k != "hard_negative_for"},
                         {k: v for k, v in first.items() if k != "hard_negative_for"})


class FormatTest(unittest.TestCase):
    def test_dump_reproduces_the_fixture_files_byte_for_byte(self):
        # a rewrite by the derivation must not reformat untouched utterances
        for language, (path, fixture) in derive.load_fixtures().items():
            with self.subTest(language=language):
                self.assertEqual(derive.dump(fixture), path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
