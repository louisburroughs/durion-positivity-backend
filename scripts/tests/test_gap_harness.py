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
import urllib.error
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = REPO_ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from gap_harness import corpus as corpus_mod  # noqa: E402
from gap_harness import api, fusion, harness, judge, questions as q_mod, refusal, report  # noqa: E402
from gap_harness import taxonomy  # noqa: E402
import rag_gap_harness as harness_cli  # noqa: E402
from gap_harness.model import (  # noqa: E402
    CAUSE_CORPUS_GAP,
    CAUSE_GENERATION,
    CAUSE_NONE,
    CAUSE_PERMISSION_GATING,
    CAUSE_RETRIEVAL_MISS,
    VERDICT_CORRECT,
    VERDICT_MISLEADING,
    VERDICT_REFUSED,
    VERDICT_UNSUPPORTED,
    VERDICTS,
    Actor,
    Capture,
    Classification,
    Grade,
    Question,
    Result,
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
        covering = self.idx.covering_docs("layaway installment plan scheduling and deposit forfeiture")
        self.assertEqual(covering, [])  # no doc covers layaway
        self.assertFalse(self.idx.exists("order.core-charges"))  # core charges are not modeled


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
        # A genuinely-uncovered topic (no corpus doc addresses layaway) with an unauthored expected
        # doc is a true corpus gap. NB: core charges are NOT such a topic — order.returns-refunds
        # documents them as "not modeled", so that case is a covered topic (see the phantom-doc tests).
        q = Question("q", "layaway installment plan scheduling and deposit forfeiture",
                     Actor("ROLE_USER", ("order:order:view",)), rag_scope="order",
                     expected_doc_ids=("order.layaway",))
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

    def test_phantom_expected_doc_but_retrieved_doc_covers_topic_is_generation(self):
        # The core-charge case: fixture names an unauthored doc, but order.returns-refunds (which
        # documents core charges as "not modeled") WAS retrieved and the answer is still wrong. That
        # is a generation failure, not a corpus gap — must not send a human to author a doc that exists.
        q = Question("q", "how are core charges handled when a customer returns a rebuildable part",
                     Actor("ROLE_USER", ("order:order:view",)), rag_scope="order",
                     expected_doc_ids=("order.core-charges",))  # phantom, unauthored
        cap = self._cap("q", ["order.returns-refunds"], ["order:order:view"])  # covering doc retrieved
        c = taxonomy.classify(q, cap, VERDICT_UNSUPPORTED, self.idx)
        self.assertEqual(c.cause, CAUSE_GENERATION)
        self.assertIn("order.returns-refunds", c.covering_doc_ids)

    def test_phantom_expected_doc_but_covering_doc_not_retrieved_is_retrieval_miss(self):
        # Copilot #1160 review: a phantom expected doc must not shortcut to corpus_gap when a covering
        # doc EXISTS but wasn't retrieved — that is a retrieval miss, not missing content. Here
        # order.returns-refunds covers the topic and is visible to the actor, but nothing was retrieved.
        q = Question("q", "how are core charges handled when a customer returns a rebuildable part",
                     Actor("ROLE_USER", ("order:order:view",)), rag_scope="order",
                     expected_doc_ids=("order.core-charges",))  # phantom, unauthored
        cap = self._cap("q", [], ["order:order:view"])  # covering doc exists + visible, but not retrieved
        c = taxonomy.classify(q, cap, VERDICT_REFUSED, self.idx)
        self.assertEqual(c.cause, CAUSE_RETRIEVAL_MISS)
        self.assertIn("order.returns-refunds", c.covering_doc_ids)

    def test_phantom_expected_doc_with_no_covering_doc_at_all_stays_corpus_gap(self):
        # Guard the guard: a phantom expected doc whose topic NO corpus doc covers is still a genuine
        # corpus gap (the loyalty known-gap probe) — the fallback content lookup finds nothing.
        q = Question("q", "how do loyalty reward points accrue and redeem at checkout",
                     Actor("ROLE_USER"), rag_scope="order", expected_doc_ids=("order.loyalty-points",))
        c = taxonomy.classify(q, self._cap("q", [], []), VERDICT_REFUSED, self.idx)
        self.assertEqual(c.cause, CAUSE_CORPUS_GAP)


class HarnessRunTest(unittest.TestCase):
    """A failed `ask` (transport/HTTP error, e.g. a 404 from a wrong base-url) must surface as an
    ungraded transport failure — never be scored as a content `refused` off the empty answer, which
    previously let a broken endpoint masquerade as a run full of refusals with transport=0."""

    @classmethod
    def setUpClass(cls):
        cls.idx = corpus_mod.load_corpus()

    def _retrieve(self, _q):
        return [], []

    def test_ask_failure_is_ungraded_transport_not_refused(self):
        def ask(_q):
            raise urllib.error.HTTPError("http://x/v1/mcp/chat", 404, "Not Found", {}, None)

        q = Question("q", "what is a sku", Actor("ROLE_USER"))
        # A judge is supplied; it must NOT be consulted for a failed ask (there is no answer to grade).
        judge_calls = []
        judge_llm = lambda p: judge_calls.append(p) or '{"verdict":"correct"}'
        results, recoveries = harness.run([q], ask, self._retrieve, self.idx, judge_llm)

        self.assertEqual(len(results), 1)
        grade = results[0].grade
        self.assertIsNotNone(grade.judge_error)
        self.assertTrue(grade.judge_error.startswith(judge.ERR_TRANSPORT))
        self.assertIn("404", grade.judge_error)
        self.assertFalse(grade.deterministic)  # not a deterministic refusal
        self.assertEqual(judge_calls, [])  # judge never called on a non-answer
        self.assertEqual(recoveries, [])  # ungraded rows never feed the flip-threshold set

        summary = report.summarize(results)
        self.assertEqual(summary["verdicts"]["refused"], 0)  # the bug this guards against
        self.assertEqual(summary["verdicts"]["ungraded"], 1)
        self.assertEqual(summary["ungraded_breakdown"]["transport"], 1)
        self.assertEqual(sum(summary["taxonomy"].values()), 0)  # taxonomy skips ungraded rows

    def test_successful_ask_still_graded(self):
        # Guard the else-branch: a clean ask is graded normally (retrieval failure alone must not
        # divert a good answer into the ungraded bucket).
        q = Question("q", "vin format", Actor("ROLE_USER"), expected_facts=("A VIN is 17 characters",))
        judge_llm = lambda _p: '{"verdict":"correct"}'
        results, _ = harness.run([q], lambda _q: "A VIN is 17 characters", self._retrieve, self.idx, judge_llm)
        self.assertIsNone(results[0].grade.judge_error)
        self.assertEqual(results[0].grade.verdict, VERDICT_CORRECT)


class WarningRemedyTest(unittest.TestCase):
    """The ungraded warning must name the right fix: chat-endpoint (ask) transport failures need the
    base-url/token fixed, judge transport failures need the judge timeout/model changed. They share
    the `transport` bucket, so the message keys off the `ask:` tag harness.run stamps on a failed ask."""

    def _res(self, judge_error):
        q = Question("q", "x", Actor("ROLE_USER"))
        cap = Capture(question_id="q", answer="", dense_docs=[], permission_codes=[])
        grade = Grade(verdict=VERDICT_MISLEADING, judge_error=judge_error)
        return Result(question=q, capture=cap, grade=grade, classification=Classification(cause=CAUSE_NONE))

    def test_chat_endpoint_transport_blames_endpoint_not_judge(self):
        results = [self._res("transport: ask: HTTPError: HTTP Error 404: Not Found") for _ in range(3)]
        w = report.summarize(results)["warning"]
        self.assertIn("CHAT-ENDPOINT", w)
        self.assertIn("--base-url", w)
        self.assertNotIn("--judge-timeout", w)  # do NOT send them at the judge

    def test_judge_transport_blames_judge(self):
        results = [self._res("transport: RuntimeError: timeout") for _ in range(3)]
        w = report.summarize(results)["warning"]
        self.assertIn("JUDGE", w)
        self.assertIn("--judge-timeout", w)
        self.assertNotIn("CHAT-ENDPOINT", w)

    def test_mixed_transport_names_both(self):
        results = [
            self._res("transport: ask: HTTPError: HTTP Error 401: Unauthorized"),
            self._res("transport: RuntimeError: timeout"),
            self._res("transport: ask: HTTPError: HTTP Error 404: Not Found"),
        ]
        w = report.summarize(results)["warning"]
        self.assertIn("CHAT-ENDPOINT", w)
        self.assertIn("JUDGE", w)


class PreflightTest(unittest.TestCase):
    """The run preflight fails fast with an actionable message on a 404/401 rather than letting all N
    questions fail one at a time and surface only as a 100%-ungraded run at the end."""

    class _Chat:
        def __init__(self, exc):
            self._exc = exc

        def ask(self, _actor, _msg):
            if self._exc:
                raise self._exc
            return "ok"

    def test_404_aborts_with_base_url_hint(self):
        exc = urllib.error.HTTPError("http://x/v1/mcp/chat", 404, "Not Found", {}, None)
        with self.assertRaises(SystemExit) as cm:
            harness_cli.preflight_chat(self._Chat(exc), Actor("ROLE_USER"), "http://localhost:8080")
        msg = str(cm.exception)
        self.assertIn("404", msg)
        self.assertIn("/mcp-server", msg)

    def test_401_aborts_with_token_hint(self):
        exc = urllib.error.HTTPError("http://x/v1/mcp/chat", 401, "Unauthorized", {}, None)
        with self.assertRaises(SystemExit) as cm:
            harness_cli.preflight_chat(self._Chat(exc), Actor("ROLE_USER"), "http://localhost:8080/mcp-server")
        self.assertIn("token", str(cm.exception).lower())

    def test_success_is_silent(self):
        harness_cli.preflight_chat(self._Chat(None), Actor("ROLE_USER"), "http://x/mcp-server")  # no raise


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
        self.assertIn("unsupported", verdicts)  # #1131: ungrounded-fabrication class must be calibrated
        # every calibration verdict is a known verdict
        self.assertTrue(set(verdicts).issubset(set(VERDICTS)))


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
            # corpus gap: expected doc missing AND no corpus doc covers the topic (layaway)
            Question("q-gap", "layaway installment plan scheduling and deposit forfeiture",
                     Actor("ROLE_USER", ("order:order:view",)),
                     rag_scope="order", expected_doc_ids=("order.layaway",), topic="layaway"),
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
        self.assertIn("layaway", gap["entries"][0]["topic"])
        md = report.render_gap_report_md(gap)
        self.assertIn("DO NOT INGEST", md)
        self.assertIn("Draft outline", md)
        summary = report.summarize(results)
        self.assertEqual(summary["taxonomy"][CAUSE_CORPUS_GAP], 1)


class UnsupportedVerdictTest(unittest.TestCase):
    """#1131: a corpus gap means no ground truth, so the grounded grader has nothing to contradict
    and would pass a fabrication as `correct`. The judge must switch to a groundedness prompt and the
    new `unsupported` verdict when no facts exist, and that failure must route to a corpus_gap."""

    @classmethod
    def setUpClass(cls):
        cls.idx = corpus_mod.load_corpus()

    def test_prompt_selects_grounded_grader_when_facts_present(self):
        p = judge.build_judge_prompt("q", "a", ["fact one"])
        self.assertIn("GROUND TRUTH", p)
        self.assertIn("fact one", p)
        self.assertNotIn("There is NO authoritative ground truth", p)

    def test_prompt_selects_groundedness_grader_when_no_facts(self):
        p = judge.build_judge_prompt("q", "a", [])
        self.assertIn("There is NO authoritative ground truth", p)
        self.assertIn("unsupported", p)
        self.assertNotIn("GROUND TRUTH (authoritative", p)

    def test_empty_string_facts_are_treated_as_no_facts(self):
        # ground_truth_for yields [] for a factless doc; a list of empty strings must not fool it.
        self.assertIn("There is NO authoritative ground truth", judge.build_judge_prompt("q", "a", ["", ""]))

    def test_fabrication_graded_unsupported(self):
        g = judge.grade("q", "A five-step Return Request workflow handles it.", [],
                        lambda _: '{"verdict":"unsupported","rationale":"invented specifics"}')
        self.assertEqual(g.verdict, VERDICT_UNSUPPORTED)
        self.assertIsNone(g.judge_error)

    def test_unsupported_routes_to_corpus_gap(self):
        # An unsupported (fabricated) answer on a genuinely-uncovered topic routes to corpus_gap. Use
        # layaway, not core charges: core charges are covered by order.returns-refunds ("not modeled"),
        # so that topic no longer routes to corpus_gap (see TaxonomyTest phantom-doc cases).
        q = Question("q-gap", "layaway installment plan scheduling and deposit forfeiture",
                     Actor("ROLE_USER", ("order:order:view",)), rag_scope="order",
                     expected_doc_ids=("order.no-such-doc",), topic="layaway")
        cap = Capture(question_id="q-gap", answer="fabricated", permission_codes=["order:order:view"])
        c = taxonomy.classify(q, cap, VERDICT_UNSUPPORTED, self.idx)
        self.assertEqual(c.cause, CAUSE_CORPUS_GAP)

    def test_unsupported_counts_in_summary_and_calibration(self):
        q = Question("u", "u", Actor("ROLE_USER"))
        r = Result(question=q, capture=Capture(question_id="u", answer="a"),
                   grade=Grade(verdict=VERDICT_UNSUPPORTED),
                   classification=Classification(cause=CAUSE_CORPUS_GAP))
        s = report.summarize([r])
        self.assertEqual(s["verdicts"][VERDICT_UNSUPPORTED], 1)
        acc = judge.calibrate([(VERDICT_UNSUPPORTED, VERDICT_UNSUPPORTED)])
        self.assertIn(VERDICT_UNSUPPORTED, acc.per_class)
        self.assertAlmostEqual(acc.per_class[VERDICT_UNSUPPORTED].recall, 1.0)


class JudgeReliabilityTest(unittest.TestCase):
    """#1129 (configurable timeout + slow-call warning) and #1130 (transport failures must not be
    counted as `misleading`; ungraded is derived from judge_error, not reported as 0)."""

    def _result(self, qid, grade, *, expected_facts=("f",)):
        q = Question(qid, qid, Actor("ROLE_USER"), expected_facts=expected_facts)
        cap = Capture(question_id=qid, answer="a")
        cls = Classification(cause=CAUSE_NONE)
        return Result(question=q, capture=cap, grade=grade, classification=cls)

    def test_transport_failure_is_tagged_and_retried(self):
        calls = {"n": 0}

        def boom(_):
            calls["n"] += 1
            raise TimeoutError("read timed out")

        g = judge.grade("q", "a real answer", ["fact"], boom, transport_retries=2)
        self.assertEqual(calls["n"], 3)  # initial + 2 retries
        self.assertTrue(g.judge_error.startswith(judge.ERR_TRANSPORT))

    def test_transport_retry_then_success(self):
        calls = {"n": 0}

        def flaky(_):
            calls["n"] += 1
            if calls["n"] == 1:
                raise ConnectionError("reset")
            return '{"verdict":"correct","rationale":"ok"}'

        g = judge.grade("q", "a real answer", ["fact"], flaky, transport_retries=2)
        self.assertEqual(g.verdict, VERDICT_CORRECT)
        self.assertIsNone(g.judge_error)

    def test_parse_failure_is_tagged_parse(self):
        self.assertTrue(judge.parse_verdict("not json").judge_error.startswith(judge.ERR_PARSE))
        self.assertTrue(judge.parse_verdict('{"verdict":"great"}').judge_error.startswith(judge.ERR_PARSE))

    def test_summary_excludes_ungraded_from_verdicts(self):
        results = [
            self._result("real-mis", Grade(verdict=VERDICT_MISLEADING, cited_ground_truth="x")),
            self._result("transport", Grade(verdict=VERDICT_MISLEADING, judge_error="transport: TimeoutError")),
            self._result("parse", Grade(verdict=VERDICT_MISLEADING, judge_error="parse: bad json")),
            self._result("ok", Grade(verdict=VERDICT_CORRECT)),
        ]
        s = report.summarize(results)
        # only the genuinely-judged misleading is counted; the two errored ones are not
        self.assertEqual(s["verdicts"][VERDICT_MISLEADING], 1)
        self.assertEqual(s["verdicts"]["ungraded"], 2)
        self.assertEqual(s["ungraded"], 2)
        self.assertEqual(s["ungraded_breakdown"]["transport"], 1)
        self.assertEqual(s["ungraded_breakdown"]["parse"], 1)
        # graded verdicts + ungraded sum to the question count
        self.assertEqual(sum(s["verdicts"].values()), s["questions"])
        # half ungraded -> loud warning
        self.assertIn("warning", s)
        self.assertIn("UNGRADED", s["warning"])

    def test_ungraded_excluded_from_taxonomy(self):
        # An ungraded (judge_error) record's taxonomy cause is a placeholder and must not be counted,
        # matching the summary warning that counts rest on the graded remainder only.
        graded = self._result("g", Grade(verdict=VERDICT_MISLEADING))
        graded.classification = Classification(cause=CAUSE_CORPUS_GAP)
        ungraded = self._result("u", Grade(verdict=VERDICT_MISLEADING, judge_error="transport: TimeoutError"))
        ungraded.classification = Classification(cause=CAUSE_CORPUS_GAP)
        s = report.summarize([graded, ungraded])
        self.assertEqual(s["taxonomy"][CAUSE_CORPUS_GAP], 1)  # only the graded one

    def test_summary_no_warning_when_fully_graded(self):
        results = [self._result("ok", Grade(verdict=VERDICT_CORRECT))]
        s = report.summarize(results)
        self.assertEqual(s["ungraded"], 0)
        self.assertNotIn("warning", s)

    def test_slow_judge_call_warns(self):
        import io
        from contextlib import redirect_stderr

        buf = io.StringIO()
        with redirect_stderr(buf):
            api._warn_if_slow("judge-model", elapsed=95.0, timeout=120.0)
            api._warn_if_slow("judge-model", elapsed=5.0, timeout=120.0)
        out = buf.getvalue()
        self.assertIn("WARNING", out)
        self.assertEqual(out.count("WARNING"), 1)  # only the 95/120 call warns

    def test_build_judge_threads_timeout(self):
        import argparse

        captured = {}

        def fake_ollama(base, model, timeout=120):
            captured["timeout"] = timeout
            return lambda p: ""

        orig = api.ollama_judge
        api.ollama_judge = fake_ollama
        try:
            args = argparse.Namespace(judge="ollama", judge_base=None, judge_model="m", judge_timeout=300)
            harness_cli.build_judge(args)
        finally:
            api.ollama_judge = orig
        self.assertEqual(captured["timeout"], 300)


if __name__ == "__main__":
    unittest.main()
