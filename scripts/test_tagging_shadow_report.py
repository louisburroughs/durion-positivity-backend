import contextlib
import io
import json
import os
import tempfile
import unittest
from unittest import mock

from scripts import tagging_shadow_report as report

# The wire shape of GET /v1/eval/turn-traces, from the records in pos-mcp-server
# internal/domain (ADR-0068 wave 1): EvalTurnTrace, TagTrace and TagTrace.TagEntry, serialised by
# Jackson with their component names and spring.jackson.default-property-inclusion: non_null, so a
# null component is ABSENT from the JSON, never "key": null. The tagging block is the component
# `tags` (TagTrace), and TagTrace carries its own `tags` list of TagEntry.
EVAL_TURN_TRACE_FIELDS = (
    "turnId", "startedAt", "completedAt", "expiresAt", "userId", "username", "role", "userMessage",
    "simpleChat", "intent", "modelTier", "workflowState", "selectedTools", "systemPrompt", "offeredTools",
    "toolCalls", "finalResponse", "error", "serverBuild", "answerSource", "conversationId", "messageId",
    "scope", "tags",
)
TAG_TRACE_FIELDS = (
    "mode", "enforcedTags", "providerModel", "latencyMs", "fallbackReason", "stateTruncated", "questionCount",
    "requestBodyBytes", "optionListHash", "tags",
)
TAG_ENTRY_FIELDS = (
    "name", "actingValue", "actingSource", "heuristicValue", "heuristicRule", "modelValue", "modelConfidence",
    "modelProbability", "threshold", "agree",
)


def _non_null(fields, values):
    """Jackson with non_null inclusion: every record component in order, nulls omitted."""
    unknown = set(values) - set(fields)
    assert not unknown, f"not a record component: {unknown}"
    return {name: values[name] for name in fields if values.get(name) is not None}


def _uuid(n):
    return f"0199b1be-7080-7000-8000-{n:012x}"


def tag_entry(name, heuristic=None, model=None, confidence=None, agree=None, threshold=0.75):
    """A TagTrace.TagEntry as serialised: acting value = heuristic in shadow (HEURISTIC source)."""
    return _non_null(TAG_ENTRY_FIELDS, {
        "name": name,
        "actingValue": heuristic if heuristic is not None else None,
        "actingSource": "HEURISTIC" if heuristic is not None else None,
        "heuristicValue": heuristic,
        "heuristicRule": "keyword" if heuristic is not None else None,
        "modelValue": model,
        "modelConfidence": confidence,
        "modelProbability": confidence,
        "threshold": threshold,
        "agree": agree,
    })


def tag_trace(entries, mode="SHADOW", model="tev1:0.8b", latency=100, fallback=None, truncated=False):
    """A TagTrace as serialised. In OFF the provider is not called: no model, latency or counts."""
    called = mode != "OFF"
    return _non_null(TAG_TRACE_FIELDS, {
        "mode": mode,
        "enforcedTags": [],
        "providerModel": model if called else None,
        "latencyMs": latency if called else None,
        "fallbackReason": fallback,
        "stateTruncated": truncated,
        "questionCount": 13 if called else None,
        "requestBodyBytes": 8123 if called else None,
        "optionListHash": "3f2a9c" if called else None,
        "tags": list(entries),
    })


def eval_turn_trace(n, message="How many open workorders?", tags=None):
    """An EvalTurnTrace as GET /v1/eval/turn-traces returns it; `tags` is the TagTrace or None."""
    return _non_null(EVAL_TURN_TRACE_FIELDS, {
        "turnId": _uuid(n),
        "startedAt": "2026-10-01T12:00:00Z",
        "completedAt": "2026-10-01T12:00:04Z",
        "expiresAt": "2026-10-02T12:00:04Z",
        "userId": _uuid(0xA11CE),
        "username": "admin.alpha",
        "role": "ROLE_ADMIN",
        "userMessage": message,
        "simpleChat": False,
        "intent": "QUERY",
        "workflowState": "IDLE",
        "selectedTools": ["WorkorderFacadeTool"],
        "offeredTools": [],
        "toolCalls": [],
        "finalResponse": "answer",
        "serverBuild": "sha-0000000",
        "conversationId": _uuid(0xC0 + n),
        "messageId": _uuid(0xD0 + n),
        "tags": tags,
    })


def shadow(n, tags=(), **kwargs):
    entries = [tag_entry(name, "QUERY", "QUERY" if agree else "ACTION", conf, agree) if conf is not None
               else tag_entry(name, "QUERY") for name, conf, agree in tags]
    return eval_turn_trace(n, f"question {n}", tag_trace(entries, **kwargs))


FIXTURE = [
    shadow(1, latency=100, tags=[("intent", 0.97, True), ("risk", 0.55, False)]),
    shadow(2, latency=200, tags=[("intent", 0.62, False), ("risk", 0.91, True)]),
    # A timeout: the turn paid the whole 800 ms budget, and the model answered nothing.
    shadow(3, latency=800, fallback="timeout", truncated=True, tags=[("intent", None, None)]),
    shadow(4, latency=400, tags=[("intent", 0.99, True)]),
    # Mode OFF: the heuristic alone, no provider call. Counted, then skipped.
    eval_turn_trace(5, "question 5", tag_trace([tag_entry("intent", "QUERY")], mode="OFF")),
    eval_turn_trace(6, "a turn recorded before ADR-0068"),
    eval_turn_trace(7, "another pre-ADR-0068 turn"),
]


def _run_main(argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = report.main(argv)
    return code, out.getvalue(), err.getvalue()


class WireShapeTest(unittest.TestCase):
    def test_fixture_is_the_eval_turn_trace_shape(self):
        trace = FIXTURE[0]
        self.assertIn("tags", trace)
        self.assertNotIn("tagging", trace)
        self.assertTrue(set(trace) <= set(EVAL_TURN_TRACE_FIELDS))
        self.assertEqual(trace["tags"]["mode"], "SHADOW")
        self.assertTrue(set(trace["tags"]) <= set(TAG_TRACE_FIELDS))
        self.assertTrue(set(trace["tags"]["tags"][0]) <= set(TAG_ENTRY_FIELDS))
        self.assertNotIn("fallbackReason", trace["tags"])  # null: omitted under non_null inclusion
        self.assertNotIn("tags", FIXTURE[5])  # pre-ADR-0068: no block at all


class ShadowReportTest(unittest.TestCase):
    def test_model_stats(self):
        m = report.build_report(FIXTURE)["models"]["tev1:0.8b"]
        self.assertEqual(m["turns"], 4)
        self.assertEqual(m["answeredTurns"], 3)
        self.assertEqual(m["fallbackByReason"], {"timeout": 0.25})
        self.assertEqual(m["stateTruncationRate"], 0.25)

    def test_latency_percentiles_exclude_fallback_turns(self):
        m = report.build_report(FIXTURE)["models"]["tev1:0.8b"]
        # over 100/200/400 only: the 800 ms timeout would make p95 800
        self.assertEqual(m["latencyP50Ms"], 200)
        self.assertEqual(m["latencyP95Ms"], 400)
        self.assertEqual(m["fallbackLatencyP50Ms"], 800)
        self.assertEqual(m["fallbackLatencyP95Ms"], 800)

    def test_fallback_latency_none_without_fallbacks(self):
        m = report.build_report(FIXTURE[:2])["models"]["tev1:0.8b"]
        self.assertIsNone(m["fallbackLatencyP50Ms"])
        self.assertEqual(m["latencyP95Ms"], 200)

    def test_mode_off_turns_skipped_entirely(self):
        rep = report.build_report(FIXTURE)
        self.assertEqual(rep["turnsModeOff"], 1)
        self.assertEqual(set(rep["models"]), {"tev1:0.8b"})  # no "unknown" model for the OFF turn
        self.assertEqual(rep["models"]["tev1:0.8b"]["turns"], 4)
        lower = report.build_report([eval_turn_trace(9, "x", tag_trace([], mode="off"))])
        self.assertEqual((lower["turnsModeOff"], lower["models"]), (1, {}))

    def test_skips_untagged_turns(self):
        self.assertEqual(report.build_report(FIXTURE)["turnsWithoutTagging"], 2)

    def test_reads_the_tags_component_not_a_tagging_key(self):
        renamed = [{("tagging" if k == "tags" else k): v for k, v in t.items()} for t in FIXTURE[:4]]
        rep = report.build_report(renamed)
        self.assertEqual(rep["models"], {})
        self.assertEqual(rep["turnsWithoutTagging"], 4)

    def test_tag_agreement_and_thresholds(self):
        intent = report.build_report(FIXTURE)["tags"]["intent"]
        self.assertEqual(intent["compared"], 3)  # turn 3 has no model answer to compare
        self.assertAlmostEqual(intent["agreementRate"], 2 / 3)
        self.assertEqual(intent["confidenceDeciles"][9], 2)
        self.assertEqual(intent["confidenceDeciles"][6], 1)
        self.assertAlmostEqual(intent["atThreshold"]["0.50"]["agreementRate"], 2 / 3)
        self.assertEqual(intent["atThreshold"]["0.95"], {"answered": 2, "agreementRate": 1.0})
        self.assertEqual(len(intent["atThreshold"]), 10)

    def test_language_filter(self):
        rep = report.build_report(FIXTURE, "fr", {_uuid(1): "fr", _uuid(2): "en"})
        self.assertEqual(rep["models"]["tev1:0.8b"]["turns"], 1)

    def test_parse_ndjson_and_array(self):
        self.assertEqual(len(report.parse_traces('{"a":1}\n{"a":2}\n')), 2)
        self.assertEqual(len(report.parse_traces('[{"a":1},{"a":2}]')), 2)
        self.assertEqual(report.parse_traces(""), [])

    def test_missing_fields_tolerated(self):
        rep = report.build_report([{"turnId": "x", "tags": {"tags": [{"name": "intent"}]}}])
        self.assertIn("unknown", rep["models"])
        self.assertEqual(rep["tags"]["intent"]["compared"], 0)

    def test_main_json_output(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as handle:
            json.dump(FIXTURE, handle)
        try:
            code, out, _ = _run_main(["--file", handle.name, "--json"])
            self.assertEqual(code, 0)
            self.assertEqual(json.loads(out)["models"]["tev1:0.8b"]["turns"], 4)
            self.assertEqual(_run_main(["--file", handle.name])[0], 0)
        finally:
            os.unlink(handle.name)

    def test_text_render(self):
        text = report.render_text(report.build_report(FIXTURE))
        self.assertIn("tev1:0.8b", text)
        self.assertIn("fallback timeout", text)
        self.assertIn("Turns in mode OFF, heuristic only (skipped): 1", text)


class MultiFileTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def _write(self, name, traces, ndjson=False):
        path = os.path.join(self.tmp.name, name)
        with open(path, "w", encoding="utf-8") as handle:
            if ndjson:
                handle.write("\n".join(json.dumps(t) for t in traces) + "\n")
            else:
                json.dump(traces, handle)
        return path

    def _turns(self, argv):
        code, out, _ = _run_main(argv + ["--json"])
        self.assertEqual(code, 0)
        return json.loads(out)["models"]["tev1:0.8b"]["turns"]

    def test_repeated_file_flags_merge(self):
        first = self._write("batch1.json", FIXTURE[:2])
        second = self._write("batch2.ndjson", FIXTURE[2:4], ndjson=True)
        self.assertEqual(self._turns(["--file", first, "--file", second]), 4)

    def test_several_paths_after_one_flag(self):
        first = self._write("batch1.json", FIXTURE[:2])
        second = self._write("batch2.json", FIXTURE[2:4])
        self.assertEqual(self._turns(["--file", first, second]), 4)

    def test_overlapping_batches_keep_each_turn_once(self):
        first = self._write("batch1.json", FIXTURE[:3])
        second = self._write("batch2.json", FIXTURE[1:4])  # turns 2 and 3 exported twice
        self.assertEqual(self._turns(["--file", first, "--file", second]), 4)

    def test_merge_traces_dedupes_on_turn_id_and_keeps_order(self):
        merged = report.merge_traces([[FIXTURE[0], FIXTURE[1]], [FIXTURE[1], FIXTURE[2]], [{"a": 1}, {"a": 1}]])
        self.assertEqual([t.get("turnId") for t in merged], [_uuid(1), _uuid(2), _uuid(3), None])

    def test_stdin_only_once(self):
        with self.assertRaises(SystemExit):
            _run_main(["--file", "-", "-"])

    def test_ground_truth_over_merged_batches(self):
        first = self._write("en-1.json", GT_TRACES[:2])
        second = self._write("en-2.json", GT_TRACES[2:])
        gate = self._write("gate.json", GATE)
        code, out, _ = _run_main(["--file", first, second, "--expected", gate, "--json"])
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)["groundTruth"]["languages"]["en"]["matchedTraces"], 3)


class FetchTest(unittest.TestCase):
    def test_request_sends_api_version_default_1(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("MCP_API_VERSION", None)
            request = report.build_request("http://gw:8080/mcp-server/", "tok", "2026-10-01T00:00:00Z", 200)
        self.assertEqual(request.get_header("X-api-version"), "1")
        self.assertEqual(request.get_header("Authorization"), "Bearer tok")
        self.assertTrue(request.full_url.startswith("http://gw:8080/mcp-server/v1/eval/turn-traces?since="))
        self.assertIn("limit=200", request.full_url)

    def test_request_api_version_from_env(self):
        with mock.patch.dict(os.environ, {"MCP_API_VERSION": "2"}):
            request = report.build_request("http://gw", "tok", "s", 10)
        self.assertEqual(request.get_header("X-api-version"), "2")

    def test_warns_when_count_reaches_the_limit(self):
        with mock.patch.object(report, "fetch_traces", return_value=FIXTURE[:3]) as fetch:
            code, _, err = _run_main(["--base-url", "http://gw", "--token", "t", "--limit", "3"])
        self.assertEqual(code, 0)
        self.assertEqual(fetch.call_args.args[3], 3)
        self.assertIn("warning: 3 traces returned", err)

    def test_no_warning_below_the_limit(self):
        with mock.patch.object(report, "fetch_traces", return_value=FIXTURE[:2]):
            _, _, err = _run_main(["--base-url", "http://gw", "--token", "t", "--limit", "3"])
        self.assertNotIn("warning", err)

    def test_limit_capped_at_the_server_maximum(self):
        with mock.patch.object(report, "fetch_traces", return_value=[]) as fetch:
            _run_main(["--base-url", "http://gw", "--token", "t", "--limit", "500"])
        self.assertEqual(fetch.call_args.args[3], report.PAGE_LIMIT)


def tag(name, heuristic=None, model=None, confidence=None):
    agree = None if heuristic is None or model is None else report._value(heuristic) == report._value(model)
    return tag_entry(name, heuristic, model, confidence, agree)


_GT_SEQ = iter(range(100, 200))


def gt_trace(message, tags):
    return eval_turn_trace(next(_GT_SEQ), message, tag_trace(tags, model="m"))


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

# An OFF turn asking a gate utterance: the heuristic alone, so it must not be scored.
GT_OFF = eval_turn_trace(199, "never asked", tag_trace([tag("simple_chat", "true")], mode="OFF"))


class GroundTruthTest(unittest.TestCase):
    def setUp(self):
        self.truth = report.build_report(GT_TRACES + [GT_OFF], expected=[GATE], verbose=True)["groundTruth"]
        self.en = self.truth["languages"]["en"]

    def test_join_counts_and_unmatched(self):
        self.assertEqual(self.en["matchedTraces"], 3)
        self.assertEqual(self.en["matchedUtterances"], 3)
        self.assertEqual(self.en["unmatchedUtterances"], 1)
        self.assertEqual(self.en["unmatchedUtteranceIds"], ["en-0004"])  # the OFF turn does not join
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
            with open(traces, "w", encoding="utf-8") as handle:
                json.dump(GT_TRACES, handle)
            with open(gate, "w", encoding="utf-8") as handle:
                json.dump(GATE, handle)
            self.assertEqual(_run_main(["--file", traces, "--expected", gate, "--verbose"])[0], 0)
            self.assertEqual(_run_main(["--file", traces, "--expected", gate, "--json"])[0], 0)
        text = report.render_text(report.build_report(GT_TRACES, expected=[GATE], verbose=True))
        self.assertIn("[en] traces joined=3", text)
        self.assertIn("no trace for: en-0004", text)


if __name__ == "__main__":
    unittest.main()
