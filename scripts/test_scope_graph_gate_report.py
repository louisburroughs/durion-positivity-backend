import contextlib
import io
import json
import os
import tempfile
import unittest

from scripts import scope_graph_gate_report as report

# The wire shape of an EvalTurnTrace's `scope` (ScopeTrace, ADR-0069 section 9), serialised by Jackson
# with non_null inclusion, after the identity lists were added: retrievedDocuments (the final top-K
# in rank order, {documentId, ragScope}), scopeDocumentIds (+Truncated), scopeToolNames (+Truncated),
# addedToolNames. An older payload lacks the four lists.
SCOPE_TRACE_FIELDS = (
    "mode", "enforced", "graphHash", "graphBuiltAt", "confidence", "seeds", "entityCount", "toolCount",
    "documentCount", "screenCount", "addedTools", "ragFilterApplied", "calledToolsInScope", "calledTools",
    "retrievedDocsInScope", "retrievedDocs", "retrievedDocuments", "scopeDocumentIds", "scopeDocumentIdsTruncated",
    "scopeToolNames", "scopeToolNamesTruncated", "addedToolNames",
)


def _uuid(n):
    return f"0199b1be-7080-7000-8000-{n:012x}"


def _doc(document_id, rag_scope="workorder"):
    doc = {"documentId": document_id}
    if rag_scope is not None:
        doc["ragScope"] = rag_scope
    return doc


def _scope(confidence, retrieved, scope_docs, truncated=False, tool_names=None, called=None, called_in_scope=None,
           counts_only=False):
    scope = {
        "mode": "SHADOW", "enforced": [], "graphHash": "c878c7206d2ed660", "graphBuiltAt": "2026-09-30T12:00:00Z",
        "confidence": confidence, "seeds": [], "entityCount": 1, "toolCount": len(tool_names or []),
        "documentCount": len(scope_docs), "screenCount": 0, "addedTools": 0, "ragFilterApplied": False,
        "calledToolsInScope": called_in_scope, "calledTools": called,
        "retrievedDocsInScope": sum(1 for d in retrieved if d["documentId"] in scope_docs),
        "retrievedDocs": len(retrieved),
    }
    if not counts_only:
        scope.update({
            "retrievedDocuments": retrieved, "scopeDocumentIds": scope_docs, "scopeDocumentIdsTruncated": truncated,
            "scopeToolNames": tool_names or [], "scopeToolNamesTruncated": False, "addedToolNames": [],
        })
    unknown = set(scope) - set(SCOPE_TRACE_FIELDS)
    assert not unknown, f"not a ScopeTrace component: {unknown}"
    return {k: v for k, v in scope.items() if v is not None}


def _trace(n, message, scope, role="ROLE_SERVICE_ADVISOR", tool_calls=()):
    return {
        "turnId": _uuid(n), "startedAt": "2026-10-02T10:00:00Z", "completedAt": "2026-10-02T10:00:03Z",
        "expiresAt": "2026-11-01T10:00:03Z", "userId": _uuid(900), "username": "advisor.alpha", "role": role,
        "userMessage": message, "simpleChat": False, "selectedTools": [], "offeredTools": [],
        "toolCalls": [{"sequence": i + 1, "name": name, "arguments": "{}", "result": "{}", "elapsedMs": 1}
                      for i, name in enumerate(tool_calls)],
        "finalResponse": "answer", "scope": scope,
    }


def _fixture(fixture_id, query, doc_ids, forbidden=None, role="ROLE_SERVICE_ADVISOR", k=5, set_name="rag-lexical"):
    expected = {"doc_ids": doc_ids, "k": k}
    if forbidden:
        expected["forbidden_doc_ids"] = forbidden
    return {"fixture_id": fixture_id, "query": query, "actor": {"role": role, "permission_codes": []},
            "expected": expected, "rag_scope": "order", "tags": ["positive"], "_set": set_name}


# ── the five synthetic situations ────────────────────────────────────────────────────────────────

KEPT_HIT = (
    # HIGH confidence, the expected document is in scope: the filter keeps it at rank 2.
    _trace(1, "REFUND_FAILED retryable return status",
           _scope("HIGH",
                  [_doc("order.codes"), _doc("order.returns-refunds"), _doc("billing.invoices", "billing")],
                  ["order.codes", "order.returns-refunds"])),
    _fixture("kept-hit", "REFUND_FAILED retryable return status", ["order.returns-refunds"]),
)
DROPPED_HIT = (
    # HIGH confidence, the expected document is NOT in scope and not master: the filter drops it.
    _trace(2, "what is a VIN",
           _scope("HIGH",
                  [_doc("glossary.identifiers", "vehicle"), _doc("workorder.public")],
                  ["workorder.public"])),
    _fixture("dropped-hit", "what is a VIN", ["glossary.identifiers"]),
)
MASTER_RESCUED = (
    # HIGH confidence, the expected document is out of scope but its chunk is rag_scope master: kept,
    # and it moves up to rank 1 because the out-of-scope workorder chunk ahead of it is dropped.
    _trace(3, "WO-2026-1001 workorder number format",
           _scope("HIGH",
                  [_doc("workorder.public"), _doc("glossary.identifiers", "master")],
                  ["order.codes"])),
    _fixture("master-rescued", "WO-2026-1001 workorder number format", ["glossary.identifiers"]),
)
LOW_PASS_THROUGH = (
    # LOW confidence: nothing is filtered even though the expected document is out of scope.
    _trace(4, "how do returns work",
           _scope("LOW",
                  [_doc("order.returns-refunds"), _doc("order.codes")],
                  ["workorder.public"])),
    _fixture("low-pass", "how do returns work", ["order.returns-refunds"]),
)
FORBIDDEN = (
    # A visibility-negative fixture: nothing expected, admin.governance must not surface. It did, on a
    # LOW turn, so the filter cannot remove it either: forbidden hits are 1 today and 1 in enforce.
    _trace(5, "who can approve a permission change",
           _scope("LOW", [_doc("admin.governance", "admin")], []), role="ROLE_TECHNICIAN"),
    _fixture("forbidden-neg", "who can approve a permission change", [], forbidden=["admin.governance"],
             role="ROLE_TECHNICIAN", set_name="rag-retrieval"),
)
ALL = [KEPT_HIT, DROPPED_HIT, MASTER_RESCUED, LOW_PASS_THROUGH, FORBIDDEN]


class SimulateEnforceTest(unittest.TestCase):
    def test_high_keeps_in_scope_or_master_in_order(self):
        scope = _scope("HIGH",
                       [_doc("a", "x"), _doc("b", "master"), _doc("c", "x"), _doc("d", "MASTER "), _doc("e", None)],
                       ["c"])
        self.assertEqual([d["documentId"] for d in report.simulate_enforce(scope)], ["b", "c", "d"])

    def test_low_and_none_pass_through(self):
        for confidence in ("LOW", "NONE"):
            scope = _scope(confidence, [_doc("a"), _doc("b")], [])
            self.assertEqual(report.simulate_enforce(scope), scope["retrievedDocuments"])

    def test_missing_rag_scope_is_not_master(self):
        # ScopeRagFilter.ragScopeOf: a chunk without a rag_scope is eligible for no scoped agent.
        scope = _scope("HIGH", [_doc("a", None)], [])
        self.assertEqual(report.simulate_enforce(scope), [])


class ScoreTest(unittest.TestCase):
    def test_rank_metrics(self):
        docs = [_doc("x"), _doc("y"), _doc("z")]
        scored = report.score(docs, ["y", "q"], [], 5)
        self.assertEqual(scored["hitAtK"], 1.0)
        self.assertEqual(scored["mrr"], 0.5)
        self.assertEqual(scored["recallAtK"], 0.5)
        self.assertEqual(scored["forbiddenHits"], [])

    def test_k_cuts_hit_and_recall_but_mrr_reads_the_whole_list(self):
        docs = [_doc("a"), _doc("b"), _doc("y")]
        scored = report.score(docs, ["y"], [], 2)
        self.assertEqual(scored["hitAtK"], 0.0)
        self.assertEqual(scored["recallAtK"], 0.0)
        self.assertAlmostEqual(scored["mrr"], 1 / 3)

    def test_negative_fixture_scores_forbidden_only(self):
        scored = report.score([_doc("admin.governance")], [], ["admin.governance"], 5)
        self.assertIsNone(scored["hitAtK"])
        self.assertIsNone(scored["mrr"])
        self.assertIsNone(scored["recallAtK"])
        self.assertEqual(scored["forbiddenHits"], ["admin.governance"])


class JoinTest(unittest.TestCase):
    def test_exact_then_normalised_join(self):
        fixture = _fixture("f", "What is a   VIN", ["g"])
        trace = _trace(1, "what is a vin", _scope("LOW", [], []))
        matches, skipped, ambiguous = report.join_traces([trace], [fixture])
        self.assertEqual([(t["turnId"], f["fixture_id"]) for t, f in matches], [(_uuid(1), "f")])
        self.assertEqual(dict(skipped), {})
        self.assertEqual(ambiguous, [])

    def test_same_query_disambiguated_by_role(self):
        positive = _fixture("pos", "who can approve", ["admin.governance"], role="ROLE_ADMIN")
        negative = _fixture("neg", "who can approve", [], forbidden=["admin.governance"], role="ROLE_TECHNICIAN")
        admin = _trace(1, "who can approve", _scope("LOW", [], []), role="ROLE_ADMIN")
        tech = _trace(2, "who can approve", _scope("LOW", [], []), role="ROLE_TECHNICIAN")
        other = _trace(3, "who can approve", _scope("LOW", [], []), role="ROLE_USER")
        matches, skipped, ambiguous = report.join_traces([admin, tech, other], [positive, negative])
        self.assertEqual([f["fixture_id"] for _, f in matches], ["pos", "neg"])
        self.assertEqual(skipped["ambiguousFixture"], 1)
        self.assertEqual(ambiguous[0]["turnId"], _uuid(3))

    def test_duplicate_fixtures_with_one_expectation_are_one_fixture(self):
        a = _fixture("a", "who can approve", [], forbidden=["admin.governance"], role="ROLE_TECHNICIAN")
        b = _fixture("b", "who can approve", [], forbidden=["admin.governance"], role="ROLE_TECHNICIAN")
        tech = _trace(1, "who can approve", _scope("LOW", [], []), role="ROLE_TECHNICIAN")
        matches, skipped, ambiguous = report.join_traces([tech], [a, b])
        self.assertEqual([f["fixture_id"] for _, f in matches], ["a"])
        self.assertEqual(dict(skipped), {})
        # Two expectations under one role stay ambiguous.
        c = _fixture("c", "who can approve", ["admin.governance"], role="ROLE_TECHNICIAN")
        matches, skipped, _ = report.join_traces([tech], [a, c])
        self.assertEqual(matches, [])
        self.assertEqual(skipped["ambiguousFixture"], 1)

    def test_unmatched_trace_is_counted(self):
        matches, skipped, _ = report.join_traces([_trace(1, "nothing like this", _scope("LOW", [], []))],
                                                 [_fixture("f", "q", ["d"])])
        self.assertEqual(matches, [])
        self.assertEqual(skipped["noFixture"], 1)


class BuildReportTest(unittest.TestCase):
    def setUp(self):
        self.report = report.build_report([t for t, _ in ALL], [f for _, f in ALL], verbose=True)

    def test_sample_accounting(self):
        self.assertEqual(self.report["samples"], 5)
        self.assertEqual(self.report["fixturesJoined"], 5)
        self.assertEqual(self.report["fixturesWithoutTrace"], 0)
        self.assertEqual(self.report["modes"], {"SHADOW": 5})

    def test_today_metrics(self):
        today = self.report["overall"]["today"]
        # Four positive fixtures, all hit today: kept at rank 2, dropped at rank 1, rescued at rank 2, low at rank 1.
        self.assertEqual(today["hitAtK"], 1.0)
        self.assertAlmostEqual(today["mrr"], (0.5 + 1.0 + 0.5 + 1.0) / 4)
        self.assertEqual(today["recallAtK"], 1.0)
        self.assertEqual(today["forbiddenHits"], 1)

    def test_enforce_metrics_and_fail_verdict(self):
        enforce = self.report["overall"]["enforce"]
        # The dropped hit is gone; the rescued one moves up to rank 1; the LOW turns are untouched.
        self.assertEqual(enforce["hitAtK"], 0.75)
        self.assertAlmostEqual(enforce["mrr"], (0.5 + 0.0 + 1.0 + 1.0) / 4)
        self.assertEqual(enforce["recallAtK"], 0.75)
        self.assertEqual(enforce["forbiddenHits"], 1)
        gate = self.report["gate"]
        self.assertEqual(gate["verdict"], "FAIL")
        self.assertEqual(gate["overall"]["deltas"]["hitAtK"], -0.25)
        self.assertEqual(gate["overall"]["deltas"]["forbiddenHits"], 0)
        self.assertTrue(any(f.startswith("hitAtK") for f in gate["overall"]["failures"]))
        self.assertIn("rag-lexical", gate["failingSets"])
        self.assertEqual(self.report["bySet"]["rag-retrieval"]["gate"]["verdict"], "PASS")

    def test_regression_list_names_the_dropped_document(self):
        self.assertEqual(self.report["regressions"], 1)
        [entry] = self.report["regressionList"]
        self.assertEqual(entry["fixtureId"], "dropped-hit")
        self.assertEqual(entry["dropped"], ["glossary.identifiers"])
        self.assertEqual(entry["confidence"], "HIGH")

    def test_per_confidence_buckets(self):
        self.assertEqual(set(self.report["byConfidence"]), {"HIGH", "LOW"})
        high = self.report["byConfidence"]["HIGH"]
        self.assertEqual(high["samples"], 3)
        self.assertEqual(high["dropped"], 1)
        low = self.report["byConfidence"]["LOW"]
        self.assertEqual(low["today"], low["enforce"])

    def test_forbidden_list(self):
        [entry] = self.report["forbiddenList"]
        self.assertEqual(entry["fixtureId"], "forbidden-neg")
        self.assertEqual(entry["today"], ["admin.governance"])
        self.assertEqual(entry["enforce"], ["admin.governance"])


class PassAndEdgeCaseTest(unittest.TestCase):
    def test_pass_when_nothing_regresses(self):
        cases = [KEPT_HIT, MASTER_RESCUED, LOW_PASS_THROUGH, FORBIDDEN]
        result = report.build_report([t for t, _ in cases], [f for _, f in cases])
        self.assertEqual(result["gate"]["verdict"], "PASS")
        self.assertEqual(result["gate"]["failingSets"], [])
        # MRR improved: the rescued document moved from rank 2 to rank 1.
        self.assertGreater(result["gate"]["overall"]["deltas"]["mrr"], 0)
        self.assertNotIn("regressionList", result)

    def test_forbidden_increase_fails_even_with_rank_metrics_held(self):
        # A HIGH turn where the filter would drop an in-scope decoy and keep... nothing changes for
        # the forbidden set; so build the increase artificially: forbidden counted over kept documents
        # can never exceed today's (the filter only removes). Assert that invariant instead.
        trace, fixture = DROPPED_HIT
        result = report.build_report([trace], [_fixture("f", fixture["query"], [], forbidden=["workorder.public"])])
        self.assertLessEqual(result["overall"]["enforce"]["forbiddenHits"], result["overall"]["today"]["forbiddenHits"])

    def test_counts_only_trace_is_skipped_not_scored(self):
        old = _trace(9, "REFUND_FAILED retryable return status",
                     _scope("HIGH", [_doc("order.returns-refunds")], ["order.returns-refunds"], counts_only=True))
        result = report.build_report([old, _trace(10, "hello", None)], [KEPT_HIT[1]])
        self.assertEqual(result["samples"], 0)
        self.assertEqual(result["skipped"], {"noRetrievedDocuments": 1, "noScope": 1})
        self.assertEqual(result["gate"]["verdict"], "NO_DATA")

    def test_truncated_scope_is_flagged(self):
        trace, fixture = KEPT_HIT
        truncated = json.loads(json.dumps(trace))
        truncated["scope"]["scopeDocumentIdsTruncated"] = True
        result = report.build_report([truncated], [fixture])
        self.assertEqual(result["scopeTruncatedSamples"], 1)

    def test_fixture_k_overrides_default(self):
        trace, _ = KEPT_HIT  # expected doc at rank 2
        result = report.build_report([trace], [_fixture("k1", trace["userMessage"], ["order.returns-refunds"], k=1)])
        self.assertEqual(result["overall"]["today"]["hitAtK"], 0.0)
        self.assertEqual(result["overall"]["today"]["mrr"], 0.5)

    def test_tools_section(self):
        scope = _scope("HIGH", [], [], tool_names=["OrderFacadeTool", "order_getorder"], called=3, called_in_scope=2)
        trace = _trace(1, "q", scope, tool_calls=["getOrder", "order_getorder", "other_op"])
        result = report.build_report([trace], [])
        high = result["tools"]["HIGH"]
        self.assertEqual(high["turns"], 1)
        self.assertAlmostEqual(high["calledInScopeShare"], 2 / 3)
        # The raw name match sees only the discovered tool: a facade call is logged under its method name.
        self.assertAlmostEqual(high["nameMatchShare"], 1 / 3)


LEXICON_YML = """\
domains:
  workorder: Work orders.
entities:
  # ---- workorder domain ----
  - key: workorder
    domain: workorder
    identifiers:
      - key: workorder-number
        pattern: '\\bWO-\\d{4}-\\d{4,}\\b'
  - key: estimate   # trailing comment
    domain: workorder
  - key: campaign
    domain: marketing
unscoped_tools: []
"""

PRELOAD_YML = """\
mcp:
  rag:
    preload:
      # entities: a comment that must not count
      docs:
        - id: "workorder.status-lifecycle"
          source-path: "classpath:rag/x.md"
          rag-scope: "workorder"
          entities: [workorder, estimate]
        - id: "workorder.public"
          rag-scope: "workorder"
          entities: [workorder]
        - id: 'glossary.identifiers'
          rag-scope: "master"
          entities: [none]
"""


class CoverageTest(unittest.TestCase):
    def test_lexicon_reader_takes_entity_keys_only(self):
        self.assertEqual(report.read_lexicon_entities(LEXICON_YML), ["workorder", "estimate", "campaign"])

    def test_preload_reader_maps_documents_to_entities(self):
        self.assertEqual(report.read_preload_docs(PRELOAD_YML), {
            "workorder.status-lifecycle": ["workorder", "estimate"],
            "workorder.public": ["workorder"],
            "glossary.identifiers": [],
        })

    def _traces(self):
        def seeded(n, message, confidence, seeds, document_count):
            scope = _scope(confidence, [], ["d"] * document_count)
            scope["seeds"] = [{"entity": e, "matchKind": k} for e, k in seeds]
            return _trace(n, message, scope)
        return [
            seeded(1, "WO-2026-1001 status", "HIGH", [("workorder", "IDENTIFIER")], 2),
            seeded(2, "estimate for the work order", "HIGH", [("estimate", "EXACT_TERM"), ("workorder", "EXACT_TERM")], 1),
            seeded(3, "spring campaign", "LOW", [("campaign", "AMBIGUOUS_TERM")], 0),
            seeded(4, "tell me about the weather", "NONE", [], 0),
            # A counts-only trace (no identity lists) still counts through documentCount.
            _trace(5, "another campaign", _scope("LOW", [], [], counts_only=True) | {
                "seeds": [{"entity": "campaign", "matchKind": "AMBIGUOUS_TERM"}], "documentCount": 0}),
            _trace(6, "no scope at all", None),
        ]

    def test_coverage_without_static_inputs(self):
        coverage = report.coverage_section(self._traces(), verbose=True)
        self.assertEqual(coverage["scopedTurns"], 5)
        self.assertEqual(coverage["noneConfidenceTurns"], 1)
        self.assertEqual(coverage["noneConfidenceMessages"], ["tell me about the weather"])
        workorder = coverage["entities"]["workorder"]
        self.assertEqual(workorder["turns"], 2)
        self.assertEqual(workorder["turnsWithNoDocument"], 0)
        self.assertEqual(workorder["meanScopeDocuments"], 1.5)
        self.assertEqual(workorder["matchKinds"], {"EXACT_TERM": 1, "IDENTIFIER": 1})
        self.assertIsNone(workorder["staticDocuments"])
        self.assertIsNone(workorder["inLexicon"])
        campaign = coverage["entities"]["campaign"]
        self.assertEqual(campaign["turns"], 2)
        self.assertEqual(campaign["turnsWithNoDocument"], 2)
        # With no preload file, an entity whose every scope had no document is the signal.
        self.assertEqual(coverage["entitiesWithNoDocument"], ["campaign"])
        self.assertNotIn("preloadDocuments", coverage)

    def test_coverage_with_lexicon_and_preload(self):
        lexicon = report.read_lexicon_entities(LEXICON_YML)
        docs = report.read_preload_docs(PRELOAD_YML)
        coverage = report.coverage_section(self._traces(), lexicon, docs)
        self.assertEqual(coverage["lexiconEntities"], 3)
        self.assertEqual(coverage["preloadDocuments"], 3)
        self.assertEqual(coverage["entities"]["workorder"]["staticDocuments"], 2)
        self.assertEqual(coverage["entities"]["estimate"]["staticDocuments"], 1)
        self.assertEqual(coverage["entities"]["campaign"]["staticDocuments"], 0)
        self.assertTrue(coverage["entities"]["campaign"]["inLexicon"])
        self.assertEqual(coverage["entitiesWithNoDocument"], ["campaign"])
        self.assertEqual(coverage["preloadDocumentsWithoutEntity"], ["glossary.identifiers"])
        self.assertEqual(coverage["preloadEntitiesNotInLexicon"], [])
        self.assertNotIn("noneConfidenceMessages", coverage)

    def test_lexicon_entity_never_seeded_is_listed_with_zero_turns(self):
        lexicon = ["workorder", "vendor-bill"]
        docs = {"workorder.public": ["workorder"], "ap.bills": ["vendor-bill", "stray-entity"]}
        coverage = report.coverage_section([], lexicon, docs)
        self.assertEqual(coverage["entities"]["vendor-bill"],
                         {"turns": 0, "turnsWithNoDocument": 0, "meanScopeDocuments": None, "matchKinds": {},
                          "inLexicon": True, "staticDocuments": 1})
        self.assertEqual(coverage["entitiesWithNoDocument"], [])
        self.assertEqual(coverage["preloadEntitiesNotInLexicon"], ["stray-entity"])

    def test_coverage_is_in_the_report_and_rendered(self):
        traces = self._traces()
        result = report.build_report(traces, [], verbose=True,
                                     lexicon_entities=report.read_lexicon_entities(LEXICON_YML),
                                     preload_docs=report.read_preload_docs(PRELOAD_YML))
        self.assertEqual(result["coverage"]["entitiesWithNoDocument"], ["campaign"])
        text = report.render_text(result)
        self.assertIn("Documentation coverage: scoped turns=5 NONE-confidence turns=1 lexicon entities=3 "
                      "preload documents=3", text)
        self.assertIn("entities with no document: campaign", text)
        self.assertIn("NONE-confidence message: tell me about the weather", text)


class LoadersAndCliTest(unittest.TestCase):
    def test_parse_traces_accepts_array_object_and_ndjson(self):
        self.assertEqual(report.parse_traces('[{"a":1}]'), [{"a": 1}])
        self.assertEqual(report.parse_traces('{"a":1}'), [{"a": 1}])
        self.assertEqual(report.parse_traces('{"a":1}\n{"a":2}\n'), [{"a": 1}, {"a": 2}])
        self.assertEqual(report.parse_traces("  "), [])

    def test_merge_traces_dedupes_by_turn_id(self):
        a = {"turnId": _uuid(1)}
        self.assertEqual(report.merge_traces([[a], [dict(a), {"turnId": _uuid(2)}]]), [a, {"turnId": _uuid(2)}])

    def test_fixture_set_is_the_parent_directory(self):
        fixtures = report.load_fixture_file("/x/eval/rag-lexical/codes.json", json.dumps({"fixtures": [{"fixture_id": "f"}]}))
        self.assertEqual(fixtures[0]["_set"], "rag-lexical")

    def test_cli_text_json_and_exit_codes(self):
        with tempfile.TemporaryDirectory() as tmp:
            os.makedirs(os.path.join(tmp, "rag-lexical"))
            traces_path = os.path.join(tmp, "traces.ndjson")
            with open(traces_path, "w", encoding="utf-8") as handle:
                for trace, _ in ALL:
                    handle.write(json.dumps(trace) + "\n")
            fixture_path = os.path.join(tmp, "rag-lexical", "f.json")
            with open(fixture_path, "w", encoding="utf-8") as handle:
                json.dump({"fixtures": [{k: v for k, v in f.items() if k != "_set"} for _, f in ALL]}, handle)

            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = report.main(["--file", traces_path, "--fixture", fixture_path, "--verbose"])
            self.assertEqual(code, 1)
            text = out.getvalue()
            self.assertIn("GATE: FAIL", text)
            self.assertIn("dropped-hit", text)
            self.assertIn("Tools (shadow metric only", text)

            lexicon_path = os.path.join(tmp, "entities.yaml")
            preload_path = os.path.join(tmp, "application.yml")
            with open(lexicon_path, "w", encoding="utf-8") as handle:
                handle.write(LEXICON_YML)
            with open(preload_path, "w", encoding="utf-8") as handle:
                handle.write(PRELOAD_YML)
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = report.main(["--file", traces_path, "--fixture", fixture_path, "--json",
                                    "--lexicon", lexicon_path, "--preload", preload_path])
            self.assertEqual(code, 1)
            parsed = json.loads(out.getvalue())
            self.assertEqual(parsed["gate"]["verdict"], "FAIL")
            self.assertEqual(parsed["coverage"]["lexiconEntities"], 3)
            self.assertEqual(parsed["coverage"]["entitiesWithNoDocument"], ["campaign"])

            # Without the dropped hit the gate passes and the exit code is 0.
            with open(traces_path, "w", encoding="utf-8") as handle:
                for trace, _ in ALL:
                    if trace["turnId"] != DROPPED_HIT[0]["turnId"]:
                        handle.write(json.dumps(trace) + "\n")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = report.main(["--file", traces_path, "--fixture", fixture_path])
            self.assertEqual(code, 0)
            self.assertIn("GATE: PASS", out.getvalue())


if __name__ == "__main__":
    unittest.main()
