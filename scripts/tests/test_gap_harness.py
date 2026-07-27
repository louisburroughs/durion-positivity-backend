"""Unit tests for the RAG corpus gap-discovery harness (#1125).

Covers the pure decision logic end to end with no live stack: corpus index + coverage lookup,
deterministic refusal detection, grounded-judge parsing + calibration math, the four-way taxonomy
(all branches), RRF recovery + flip criterion, report rendering, question sourcing, and a full
offline pipeline run driven by fakes. Also validates the shipped question/calibration data and that
the corpus manifest stays in sync with rag_seed.py.
"""

import importlib.util
import json
import sys
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = REPO_ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from gap_harness import corpus as corpus_mod  # noqa: E402
from gap_harness import fusion, harness, judge, questions as q_mod, refusal, report  # noqa: E402
from gap_harness import taxonomy  # noqa: E402
from gap_harness.model import (  # noqa: E402
    CAUSE_CORPUS_GAP,
    CAUSE_GENERATION,
    CAUSE_NONE,
    CAUSE_PERMISSION_GATING,
    CAUSE_RETRIEVAL_MISS,
    VERDICT_CORRECT,
    VERDICT_MISLEADING,
    VERDICT_REFUSED,
    Actor,
    Capture,
    Question,
    RetrievedDoc,
)

EVAL = REPO_ROOT / "pos-mcp-server/src/test/resources/eval"


def _load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class CorpusTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.idx = corpus_mod.load_corpus()

    def test_manifest_in_sync_with_rag_seed(self):
        rag_seed = _load_module("rag_seed", SCRIPTS / "rag_seed.py")
        # Compare (doc_id, source_file, scope, sorted perms) tuples so ordering of perms doesn't matter.
        def norm(m):
            return sorted((d, f, s, tuple(sorted(p))) for d, f, s, p in m)

        self.assertEqual(norm(corpus_mod.MANIFEST), norm(rag_seed.MANIFEST))

    def test_all_docs_load_with_content(self):
        # Assert against the manifest (the source of truth, kept in sync with rag_seed.py) rather
        # than a hard-coded count, so routine corpus growth doesn't fail this test spuriously.
        self.assertEqual(len(self.idx.all_doc_ids()), len(corpus_mod.MANIFEST))
        for doc_id in self.idx.all_doc_ids():
            self.assertTrue(self.idx.get(doc_id).content, f"{doc_id} has empty content")

    def test_public_doc_visible_to_anyone(self):
        glossary = self.idx.get("glossary.identifiers")
        self.assertTrue(glossary.visible_to(set()))

    def test_gated_doc_requires_permission(self):
        admin = self.idx.get("admin.governance")
        self.assertFalse(admin.visible_to(set()))
        self.assertFalse(admin.visible_to({"order:order:view"}))
        self.assertTrue(admin.visible_to({"security:permission:view"}))

    def test_verified_facts_extracted(self):
        glossary = self.idx.get("glossary.identifiers")
        joined = " ".join(glossary.verified_facts)
        self.assertIn("pos-catalog", joined)
        self.assertTrue(any("VinUtils" in f for f in glossary.verified_facts))

    def test_coverage_lookup_finds_topic(self):
        covering = self.idx.covering_docs("what is the format of a VIN identifier")
        self.assertIn("glossary.identifiers", covering)

    def test_coverage_lookup_empty_for_uncovered_topic(self):
        covering = self.idx.covering_docs("core charge refund policy for rebuildable parts warranty return")
        self.assertNotIn("order.returns-refunds", covering)  # doc does not exist
        self.assertFalse(self.idx.exists("order.returns-refunds"))


class RefusalTest(unittest.TestCase):
    def test_detects_refusals(self):
        for text in [
            "I can't answer that question.",
            "I'm unable to help with this request.",
            "I don't have access to that information.",
            "No relevant documentation was found.",
            "You don't have permission to view this.",
            "",
            "   ",
        ]:
            self.assertTrue(refusal.is_refusal(text), text)

    def test_accepts_real_answers(self):
        for text in [
            "A VIN is exactly 17 characters long.",
            "Purchase orders are owned by pos-inventory.",
            "The workorder number format is WO-YYYY-NNNN.",
        ]:
            self.assertFalse(refusal.is_refusal(text), text)

    def test_refusal_match_reports_phrase(self):
        self.assertEqual(refusal.refusal_match("hi there, all good"), "")
        self.assertTrue(refusal.refusal_match("I cannot help you"))


class JudgeTest(unittest.TestCase):
    def test_parse_plain_json(self):
        g = judge.parse_verdict('{"verdict": "correct", "rationale": "matches", "cited_ground_truth": "x"}')
        self.assertEqual(g.verdict, VERDICT_CORRECT)
        self.assertEqual(g.cited_ground_truth, "x")

    def test_parse_fenced_json(self):
        raw = "Here is my judgment:\n```json\n{\"verdict\": \"misleading\", \"rationale\": \"wrong\"}\n```"
        self.assertEqual(judge.parse_verdict(raw).verdict, VERDICT_MISLEADING)

    def test_unparseable_is_conservative(self):
        g = judge.parse_verdict("the answer looks fine to me")
        self.assertEqual(g.verdict, VERDICT_MISLEADING)
        self.assertIsNotNone(g.judge_error)

    def test_unknown_verdict_flagged(self):
        g = judge.parse_verdict('{"verdict": "great"}')
        self.assertIsNotNone(g.judge_error)

    def test_refusal_prefilter_skips_judge(self):
        calls = []

        def llm(_):
            calls.append(1)
            return '{"verdict":"correct"}'

        g = judge.grade("q", "I don't have that information", ["fact"], llm)
        self.assertEqual(g.verdict, VERDICT_REFUSED)
        self.assertTrue(g.deterministic)
        self.assertEqual(calls, [])  # judge not called

    def test_judge_invoked_for_real_answer(self):
        g = judge.grade("q", "A VIN is 16 chars", ["A VIN is 17 chars"], lambda _: '{"verdict":"misleading","cited_ground_truth":"17 chars"}')
        self.assertEqual(g.verdict, VERDICT_MISLEADING)

    def test_no_llm_marks_ungraded(self):
        g = judge.grade("q", "some real answer text", ["fact"], None)
        self.assertTrue(g.judge_error.startswith("ungraded"))

    def test_judge_exception_surfaced(self):
        def boom(_):
            raise RuntimeError("timeout")

        g = judge.grade("q", "a real answer", ["fact"], boom)
        self.assertEqual(g.verdict, VERDICT_MISLEADING)
        self.assertIn("timeout", g.judge_error)

    def test_calibration_metrics(self):
        # 2 misleading actual: 1 caught (tp), 1 called correct (fn). 1 misleading fp (a correct
        # answer judged misleading). agreement = matches / n.
        pairs = [
            (VERDICT_CORRECT, VERDICT_CORRECT),
            (VERDICT_MISLEADING, VERDICT_MISLEADING),
            (VERDICT_CORRECT, VERDICT_MISLEADING),  # missed a misleading (fn)
            (VERDICT_MISLEADING, VERDICT_CORRECT),  # false alarm (fp)
            (VERDICT_REFUSED, VERDICT_REFUSED),
        ]
        acc = judge.calibrate(pairs)
        self.assertEqual(acc.n, 5)
        self.assertAlmostEqual(acc.agreement, 3 / 5)
        m = acc.misleading
        self.assertEqual(m.support, 2)
        self.assertAlmostEqual(m.recall, 0.5)  # caught 1 of 2 actual misleading
        self.assertAlmostEqual(m.precision, 0.5)  # 1 of 2 predicted misleading was right


class TaxonomyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.idx = corpus_mod.load_corpus()

    def _cap(self, question_id, dense_ids, perms):
        return Capture(
            question_id=question_id,
            answer="whatever",
            dense_docs=[RetrievedDoc(d, 0.7) for d in dense_ids],
            permission_codes=list(perms),
        )

    def test_correct_answer_is_none(self):
        q = Question("q", "x", Actor("ROLE_USER"), expected_doc_ids=("order.guide",))
        c = taxonomy.classify(q, self._cap("q", [], []), VERDICT_CORRECT, self.idx)
        self.assertEqual(c.cause, CAUSE_NONE)

    def test_corpus_gap_when_expected_doc_absent(self):
        q = Question("q", "returns policy", Actor("ROLE_USER", ("order:order:view",)),
                     rag_scope="order", expected_doc_ids=("order.returns-refunds",))
        c = taxonomy.classify(q, self._cap("q", [], ["order:order:view"]), VERDICT_REFUSED, self.idx)
        self.assertEqual(c.cause, CAUSE_CORPUS_GAP)

    def test_corpus_gap_when_no_doc_covers_unknown_topic(self):
        q = Question("q", "how do loyalty reward points accrue and redeem at checkout", Actor("ROLE_USER"))
        c = taxonomy.classify(q, self._cap("q", [], []), VERDICT_MISLEADING, self.idx)
        self.assertEqual(c.cause, CAUSE_CORPUS_GAP)

    def test_generation_when_relevant_doc_retrieved_but_wrong(self):
        q = Question("q", "vin format", Actor("ROLE_USER"), expected_doc_ids=("glossary.identifiers",))
        cap = self._cap("q", ["glossary.identifiers"], [])
        c = taxonomy.classify(q, cap, VERDICT_MISLEADING, self.idx)
        self.assertEqual(c.cause, CAUSE_GENERATION)

    def test_permission_gating_when_only_covering_doc_is_gated(self):
        q = Question("q", "admin approvals", Actor("ROLE_USER"), rag_scope="admin",
                     expected_doc_ids=("admin.governance",))
        cap = self._cap("q", [], [])  # actor holds no perms; admin.governance filtered out
        c = taxonomy.classify(q, cap, VERDICT_REFUSED, self.idx)
        self.assertEqual(c.cause, CAUSE_PERMISSION_GATING)
        self.assertIn("admin.governance", c.gated_doc_ids)

    def test_retrieval_miss_when_visible_doc_not_retrieved(self):
        q = Question("q", "order status", Actor("ROLE_USER", ("order:order:view",)),
                     rag_scope="order", expected_doc_ids=("order.guide",))
        cap = self._cap("q", ["pricing.guide"], ["order:order:view"])  # visible doc exists, not retrieved
        c = taxonomy.classify(q, cap, VERDICT_REFUSED, self.idx)
        self.assertEqual(c.cause, CAUSE_RETRIEVAL_MISS)
        self.assertIn("order.guide", c.covering_doc_ids)


class FusionTest(unittest.TestCase):
    def test_rrf_fuse_orders_by_reciprocal_rank(self):
        fused = fusion.rrf_fuse([["a", "b", "c"], ["c", "d"]], k=60)
        self.assertEqual(fused[0], "c")  # appears in both lists -> highest fused score

    def test_recovery_record_lexical_recovers_dense_miss(self):
        rec = fusion.recovery_record("q", ["glossary.identifiers"], dense=["order.guide"],
                                     lexical=["glossary.identifiers"], k=5)
        self.assertFalse(rec.dense_hit)
        self.assertTrue(rec.lexical_hit)
        self.assertTrue(rec.recovered_by_lexical)
        self.assertTrue(rec.recovered_by_hybrid)

    def test_flip_recommended_above_threshold(self):
        recs = [
            fusion.recovery_record(f"q{i}", ["d"], dense=["x"], lexical=["d"], k=5)
            for i in range(4)
        ] + [fusion.recovery_record("q4", ["d"], dense=["x"], lexical=["y"], k=5)]
        decision = fusion.flip_decision(recs, recall_at_k=0.9, min_recovery_rate=0.30)
        self.assertEqual(decision.dense_missed, 5)
        self.assertEqual(decision.recovered_by_hybrid, 4)
        self.assertTrue(decision.recommend_flip)

    def test_flip_held_below_threshold(self):
        recs = [fusion.recovery_record("q0", ["d"], dense=["x"], lexical=["y"], k=5)]
        decision = fusion.flip_decision(recs, recall_at_k=0.9, min_recovery_rate=0.30)
        self.assertFalse(decision.recommend_flip)

    def test_flip_held_when_recall_below_floor(self):
        recs = [fusion.recovery_record(f"q{i}", ["d"], dense=["x"], lexical=["d"], k=5) for i in range(5)]
        decision = fusion.flip_decision(recs, recall_at_k=0.5, recall_floor=0.85, min_recovery_rate=0.30)
        self.assertFalse(decision.recommend_flip)
        self.assertIn("floor", decision.rationale)

    def test_no_misses_no_flip(self):
        decision = fusion.flip_decision([], recall_at_k=0.9)
        self.assertFalse(decision.recommend_flip)

    def test_flip_held_when_recall_unknown(self):
        # recall_at_k=None must NOT satisfy the recall gate: without the retrieval-quality value we
        # cannot confirm no regression, so hold the flag even when recovery clears the bar.
        recs = [fusion.recovery_record(f"q{i}", ["d"], dense=["x"], lexical=["d"], k=5) for i in range(5)]
        decision = fusion.flip_decision(recs, recall_at_k=None, min_recovery_rate=0.30)
        self.assertFalse(decision.recommend_flip)
        self.assertIn("not supplied", decision.rationale)


class RetrievalCollapseTest(unittest.TestCase):
    """DbRetriever._collapse is pure (no DB): permission-filter, collapse chunks to distinct docs,
    cap at top-k. Importing the module is safe offline (eval_live is imported lazily in connect())."""

    def setUp(self):
        from gap_harness.retrieval import DbRetriever

        self._collapse = DbRetriever._collapse

    def test_caps_at_k_distinct_docs(self):
        # 12 distinct docs across the rows; only the first k must be returned as retrieved context.
        rows = [(f"doc-{i}", None, 0.9 - i * 0.01) for i in range(12)]
        out = self._collapse(rows, set(), k=5)
        self.assertEqual([d.doc_id for d in out], [f"doc-{i}" for i in range(5)])

    def test_collapses_chunks_to_distinct_docs(self):
        rows = [("a", None, 0.9), ("a", None, 0.8), ("b", None, 0.7)]
        out = self._collapse(rows, set(), k=5)
        self.assertEqual([d.doc_id for d in out], ["a", "b"])

    def test_drops_permission_gated_docs(self):
        rows = [("public", None, 0.9), ("gated", "security:permission:view", 0.8)]
        out = self._collapse(rows, set(), k=5)
        self.assertEqual([d.doc_id for d in out], ["public"])
        out2 = self._collapse(rows, {"security:permission:view"}, k=5)
        self.assertEqual({d.doc_id for d in out2}, {"public", "gated"})


class QuestionSourcingTest(unittest.TestCase):
    def test_load_shipped_questions(self):
        qs = q_mod.load_question_files([EVAL / "gap-harness/questions.json"])
        self.assertGreaterEqual(len(qs), 10)
        ids = {q.id for q in qs}
        self.assertIn("gap-vin-format", ids)

    def test_from_rag_fixtures(self):
        qs = q_mod.from_rag_fixtures([EVAL / "rag-retrieval"])
        self.assertTrue(qs)
        self.assertTrue(all(q.source == "fixture" for q in qs))
        self.assertTrue(all(q.expected_doc_ids for q in qs))

    def test_dedupe_keeps_first(self):
        a = Question("a", "How do I look up an order?", Actor("ROLE_USER"), expected_doc_ids=("order.guide",))
        b = Question("b", "how do i look up an order?", Actor("ROLE_USER"), source="usage")
        out = q_mod.dedupe([a, b])
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0].id, "a")

    def test_from_usage_rows(self):
        qs = q_mod.from_usage_rows([{"utterance": "where is my part"}, {"query": "vin lookup"}])
        self.assertEqual(len(qs), 2)
        self.assertTrue(all(q.source == "usage" for q in qs))


class DataValidationTest(unittest.TestCase):
    def test_questions_ground_truth_source_verified(self):
        idx = corpus_mod.load_corpus()
        qs = q_mod.load_question_files([EVAL / "gap-harness/questions.json"])
        known_gap_seen = False
        for q in qs:
            for doc_id in q.expected_doc_ids:
                if "known-gap" in q.tags:
                    known_gap_seen = True
                    self.assertFalse(idx.exists(doc_id), f"{q.id} tagged known-gap but {doc_id} exists")
                else:
                    self.assertTrue(idx.exists(doc_id), f"{q.id} expects missing doc {doc_id}")
        self.assertTrue(known_gap_seen, "expected at least one known-gap question for the round-trip demo")

    def test_calibration_parses_and_has_misleading_cases(self):
        data = json.loads((EVAL / "gap-harness/calibration.json").read_text())
        verdicts = [i["human_verdict"] for i in data["items"]]
        self.assertIn("misleading", verdicts)
        self.assertIn("refused", verdicts)
        self.assertIn("correct", verdicts)


class PipelineTest(unittest.TestCase):
    """Full offline pipeline via fakes: no API, DB, or LLM."""

    @classmethod
    def setUpClass(cls):
        cls.idx = corpus_mod.load_corpus()

    def test_end_to_end_run(self):
        qs = [
            # correct: right doc retrieved, judge says correct
            Question("q-ok", "vin format", Actor("ROLE_USER"), rag_scope="master",
                     expected_doc_ids=("glossary.identifiers",), expected_facts=("17 chars",)),
            # corpus gap: expected doc missing
            Question("q-gap", "returns policy", Actor("ROLE_USER", ("order:order:view",)),
                     rag_scope="order", expected_doc_ids=("order.returns-refunds",), topic="returns"),
            # retrieval miss: visible doc exists but wrong doc retrieved
            Question("q-miss", "order status", Actor("ROLE_USER", ("order:order:view",)),
                     rag_scope="order", expected_doc_ids=("order.guide",)),
        ]

        answers = {
            "q-ok": "A VIN is 17 characters.",
            "q-gap": "I don't have that information.",
            "q-miss": "I couldn't find anything on that.",
        }
        retrieved = {
            "q-ok": ([RetrievedDoc("glossary.identifiers", 0.8)], []),
            "q-gap": ([], []),
            "q-miss": ([RetrievedDoc("pricing.guide", 0.6)], [RetrievedDoc("order.guide", 0.4)]),
        }

        def ask(q):
            return answers[q.id]

        def retrieve(q):
            return retrieved[q.id]

        def fake_judge(prompt):
            return '{"verdict":"correct","rationale":"ok"}'

        results, recoveries = harness.run(qs, ask, retrieve, self.idx, fake_judge)
        by_id = {r.question.id: r for r in results}
        self.assertEqual(by_id["q-ok"].grade.verdict, VERDICT_CORRECT)
        self.assertEqual(by_id["q-ok"].classification.cause, CAUSE_NONE)
        self.assertEqual(by_id["q-gap"].grade.verdict, VERDICT_REFUSED)  # deterministic prefilter
        self.assertEqual(by_id["q-gap"].classification.cause, CAUSE_CORPUS_GAP)
        self.assertEqual(by_id["q-miss"].classification.cause, CAUSE_RETRIEVAL_MISS)

        # one retrieval miss, recovered by lexical (order.guide in lexical, not dense)
        self.assertEqual(len(recoveries), 1)
        self.assertTrue(recoveries[0].recovered_by_hybrid)
        decision = fusion.flip_decision(recoveries, recall_at_k=0.9)
        self.assertTrue(decision.recommend_flip)

        # reports render without error
        gap = report.build_gap_report(results)
        self.assertEqual(gap["gap_count"], 1)
        self.assertIn("returns", gap["entries"][0]["topic"])
        md = report.render_gap_report_md(gap)
        self.assertIn("DO NOT INGEST", md)
        self.assertIn("Draft outline", md)
        summary = report.summarize(results)
        self.assertEqual(summary["taxonomy"][CAUSE_CORPUS_GAP], 1)


if __name__ == "__main__":
    unittest.main()
