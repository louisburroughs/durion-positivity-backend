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


if __name__ == "__main__":
    unittest.main()
