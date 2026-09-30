import json
import os
import tempfile
import unittest

from scripts import tagging_shadow_report as report


def trace(turn, model="tev1:0.8b", latency=100, fallback=None, truncated=False, tags=()):
    return {
        "turnId": turn,
        "tagging": {
            "mode": "shadow",
            "providerModel": model,
            "latencyMs": latency,
            "fallbackReason": fallback,
            "stateTruncated": truncated,
            "tags": [
                {"name": n, "modelConfidence": c, "agree": a, "actingValue": "x"} for n, c, a in tags
            ],
        },
    }


FIXTURE = [
    trace("t1", latency=100, tags=[("intent", 0.97, True), ("risk", 0.55, False)]),
    trace("t2", latency=200, tags=[("intent", 0.62, False), ("risk", 0.91, True)]),
    trace("t3", latency=300, fallback="timeout", truncated=True, tags=[("intent", None, None)]),
    trace("t4", latency=400, tags=[("intent", 0.99, True)]),
    {"turnId": "old", "tagging": None},
    {"turnId": "older"},
]


class ShadowReportTest(unittest.TestCase):
    def test_model_stats(self):
        m = report.build_report(FIXTURE)["models"]["tev1:0.8b"]
        self.assertEqual(m["turns"], 4)
        self.assertEqual(m["latencyP50Ms"], 200)
        self.assertEqual(m["latencyP95Ms"], 400)
        self.assertEqual(m["fallbackByReason"], {"timeout": 0.25})
        self.assertEqual(m["stateTruncationRate"], 0.25)

    def test_skips_untagged_turns(self):
        self.assertEqual(report.build_report(FIXTURE)["turnsWithoutTagging"], 2)

    def test_tag_agreement_and_thresholds(self):
        intent = report.build_report(FIXTURE)["tags"]["intent"]
        self.assertEqual(intent["compared"], 3)  # t3 has no answer to compare
        self.assertAlmostEqual(intent["agreementRate"], 2 / 3)
        self.assertEqual(intent["confidenceDeciles"][9], 2)
        self.assertEqual(intent["confidenceDeciles"][6], 1)
        self.assertAlmostEqual(intent["atThreshold"]["0.50"]["agreementRate"], 2 / 3)
        self.assertEqual(intent["atThreshold"]["0.95"], {"answered": 2, "agreementRate": 1.0})
        self.assertEqual(len(intent["atThreshold"]), 10)

    def test_language_filter(self):
        rep = report.build_report(FIXTURE, "fr", {"t1": "fr", "t2": "en"})
        self.assertEqual(rep["models"]["tev1:0.8b"]["turns"], 1)

    def test_parse_ndjson_and_array(self):
        self.assertEqual(len(report.parse_traces('{"a":1}\n{"a":2}\n')), 2)
        self.assertEqual(len(report.parse_traces('[{"a":1},{"a":2}]')), 2)
        self.assertEqual(report.parse_traces(""), [])

    def test_missing_fields_tolerated(self):
        rep = report.build_report([{"turnId": "x", "tagging": {"tags": [{"name": "intent"}]}}])
        self.assertIn("unknown", rep["models"])
        self.assertEqual(rep["tags"]["intent"]["compared"], 0)

    def test_main_json_output(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as handle:
            json.dump(FIXTURE, handle)
        try:
            self.assertEqual(report.main(["--file", handle.name, "--json"]), 0)
            self.assertEqual(report.main(["--file", handle.name]), 0)
        finally:
            os.unlink(handle.name)

    def test_text_render(self):
        text = report.render_text(report.build_report(FIXTURE))
        self.assertIn("tev1:0.8b", text)
        self.assertIn("fallback timeout", text)


def tag(name, heuristic=None, model=None, confidence=None):
    return {"name": name, "heuristicValue": heuristic, "modelValue": model, "modelConfidence": confidence}


def gt_trace(message, tags):
    return {"turnId": message, "userMessage": message, "tagging": {"providerModel": "m", "tags": tags}}


def utterance(uid, text, simple_chat=False, admin=False, workflow="IDLE", entity=()):
    return {"id": uid, "text": text, "expected_tags": {
        "simple_chat": simple_chat, "admin_account_question": admin, "workflow_state": workflow,
        "entity": list(entity)}}


GATE = {"language": "en", "reviewed": True, "utterances": [
    utterance("en-0001", "Is the store open?", entity=["location"]),
    utterance("en-0002", "yes"),
    utterance("en-0003", "Who has access to the audit log?", admin=True),
    utterance("en-0004", "never asked"),
]}

GT_TRACES = [
    gt_trace("Is the store open?", [
        tag("simple_chat", "false", "false", 0.9),
        tag("workflow_state", "CREATING_PO", "IDLE", 0.8),
        tag("admin_account_question", "false", "true", 0.9),
        tag("entity_location", None, "true", 0.9),
        tag("entity_stock-item", None, "true", 0.9),
    ]),
    gt_trace("  YES ", [  # joins "yes" only through the normalised comparison
        tag("simple_chat", "true", "false", 0.6),
        tag("workflow_state", "IDLE", "IDLE", 0.9),
        tag("admin_account_question", "false", "false", 0.9),
    ]),
    gt_trace("Who has access to the audit log?", [
        tag("simple_chat", "false", "false", 0.99),
        tag("workflow_state", "IDLE", "IDLE", 0.99),
        tag("admin_account_question", False, True, 0.95),  # booleans compare like their names
    ]),
    gt_trace("a message no utterance asks", [tag("simple_chat", "false", "false", 0.9)]),
]


class GroundTruthTest(unittest.TestCase):
    def setUp(self):
        self.truth = report.build_report(GT_TRACES, expected=[GATE], verbose=True)["groundTruth"]
        self.en = self.truth["languages"]["en"]

    def test_join_counts_and_unmatched(self):
        self.assertEqual(self.en["matchedTraces"], 3)
        self.assertEqual(self.en["matchedUtterances"], 3)
        self.assertEqual(self.en["unmatchedUtterances"], 1)
        self.assertEqual(self.en["unmatchedUtteranceIds"], ["en-0004"])
        self.assertEqual(self.truth["unmatchedTraces"], 1)
        self.assertEqual(self.truth["unmatchedTraceMessages"], ["a message no utterance asks"])

    def test_unmatched_lists_only_when_verbose(self):
        quiet = report.build_report(GT_TRACES, expected=[GATE])["groundTruth"]
        self.assertNotIn("unmatchedTraceMessages", quiet)
        self.assertNotIn("unmatchedUtteranceIds", quiet["languages"]["en"])

    def test_heuristic_and_merged_accuracy(self):
        chat = self.en["tags"]["simple_chat"]
        self.assertAlmostEqual(chat["heuristicAccuracy"], 2 / 3)
        self.assertAlmostEqual(chat["atThreshold"]["0.50"]["modelAccuracy"], 1.0)
        # at 0.95 only the 0.99 answer is trusted; the rest fall back to the heuristic
        self.assertAlmostEqual(chat["atThreshold"]["0.95"]["modelAccuracy"], 2 / 3)

    def test_false_non_idle_rate(self):
        workflow = self.en["tags"]["workflow_state"]
        self.assertAlmostEqual(workflow["heuristicFalseNonIdleRate"], 1 / 3)
        self.assertEqual(workflow["atThreshold"]["0.50"]["falseNonIdleRate"], 0.0)
        self.assertAlmostEqual(workflow["atThreshold"]["0.85"]["falseNonIdleRate"], 1 / 3)

    def test_model_true_heuristic_false_rate_and_precision(self):
        admin = self.en["tags"]["admin_account_question"]
        low, high = admin["atThreshold"]["0.50"], admin["atThreshold"]["0.95"]
        self.assertAlmostEqual(low["modelTrueHeuristicFalseRate"], 2 / 3)
        self.assertAlmostEqual(low["modelTrueHeuristicFalsePrecision"], 0.5)
        self.assertAlmostEqual(high["modelTrueHeuristicFalseRate"], 1 / 3)
        self.assertEqual(high["modelTrueHeuristicFalsePrecision"], 1.0)
        self.assertNotIn("falseNonIdleRate", admin["atThreshold"]["0.50"])

    def test_entity_set_precision_recall(self):
        low, high = self.en["entity"]["0.50"], self.en["entity"]["0.95"]
        self.assertEqual((low["predicted"], low["expected"]), (2, 1))
        self.assertEqual(low["precision"], 0.5)
        self.assertEqual(low["recall"], 1.0)
        self.assertIsNone(high["precision"])
        self.assertEqual(high["recall"], 0.0)

    def test_join_keys(self):
        self.assertEqual(report.normalise_text("  Hello \n  World "), "hello world")
        matches, unmatched, missing = report.join_traces(GT_TRACES, GATE)
        self.assertEqual([u["id"] for _, u in matches], ["en-0001", "en-0002", "en-0003"])
        self.assertEqual(len(unmatched), 1)
        self.assertEqual([u["id"] for u in missing], ["en-0004"])

    def test_two_languages_reported_separately(self):
        fr = {"language": "fr-CA", "reviewed": False,
              "utterances": [utterance("fr-CA-0001", "Is the store open?")]}
        truth = report.build_report(GT_TRACES, expected=[GATE, fr])["groundTruth"]
        self.assertEqual(set(truth["languages"]), {"en", "fr-CA"})
        self.assertEqual(truth["languages"]["fr-CA"]["matchedTraces"], 1)
        self.assertEqual(truth["unmatchedTraces"], 1)

    def test_main_with_expected(self):
        with tempfile.TemporaryDirectory() as tmp:
            traces, gate = os.path.join(tmp, "t.json"), os.path.join(tmp, "g.json")
            json.dump(GT_TRACES, open(traces, "w", encoding="utf-8"))
            json.dump(GATE, open(gate, "w", encoding="utf-8"))
            self.assertEqual(report.main(["--file", traces, "--expected", gate, "--verbose"]), 0)
            self.assertEqual(report.main(["--file", traces, "--expected", gate, "--json"]), 0)
        text = report.render_text(report.build_report(GT_TRACES, expected=[GATE], verbose=True))
        self.assertIn("[en] traces joined=3", text)
        self.assertIn("no trace for: en-0004", text)


if __name__ == "__main__":
    unittest.main()
