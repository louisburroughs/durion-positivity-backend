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


def _enforce(trace, retrieved, n=None, enforced=("RAG",), graph_hash=None):
    """The enforce run's turn for the same question: a new turnId, mode ENFORCE, its own top-K."""
    copy = json.loads(json.dumps(trace))
    copy["turnId"] = _uuid(n if n is not None else 500 + int(trace["turnId"][-12:], 16))
    copy["scope"].update({"mode": "ENFORCE", "enforced": list(enforced), "retrievedDocuments": retrieved,
                          "ragFilterApplied": True})
    if graph_hash:
        copy["scope"]["graphHash"] = graph_hash
    return copy


# The enforce run of the five situations, as the real hook would serve them when it agrees with the
# replay: billing.invoices (out of scope) gone, the dropped hit gone, the master chunk at rank 1.
ENFORCE_ALL = [
    _enforce(KEPT_HIT[0], [_doc("order.codes"), _doc("order.returns-refunds")]),
    _enforce(DROPPED_HIT[0], [_doc("workorder.public")]),
    _enforce(MASTER_RESCUED[0], [_doc("glossary.identifiers", "master")]),
    _enforce(LOW_PASS_THROUGH[0], LOW_PASS_THROUGH[0]["scope"]["retrievedDocuments"]),
    _enforce(FORBIDDEN[0], FORBIDDEN[0]["scope"]["retrievedDocuments"]),
]
PASSING = [KEPT_HIT, MASTER_RESCUED, LOW_PASS_THROUGH, FORBIDDEN]
ENFORCE_PASSING = [ENFORCE_ALL[0], ENFORCE_ALL[2], ENFORCE_ALL[3], ENFORCE_ALL[4]]


def _ids(joined, fixtures):
    return {fixtures[i]["fixture_id"]: t["turnId"] for i, t in joined.items()}


class JoinTest(unittest.TestCase):
    def test_exact_then_normalised_join(self):
        fixture = _fixture("f", "What is a   VIN", ["g"])
        trace = _trace(1, "what is a vin", _scope("LOW", [], []))
        joined, skipped, mismatches = report.join_traces([trace], [fixture])
        self.assertEqual(_ids(joined, [fixture]), {"f": _uuid(1)})
        self.assertEqual(dict(skipped), {})
        self.assertEqual(mismatches, [])

    def test_same_query_settled_by_role(self):
        positive = _fixture("pos", "who can approve", ["admin.governance"], role="ROLE_ADMIN")
        negative = _fixture("neg", "who can approve", [], forbidden=["admin.governance"], role="ROLE_TECHNICIAN")
        admin = _trace(1, "who can approve", _scope("LOW", [], []), role="ROLE_ADMIN")
        tech = _trace(2, "who can approve", _scope("LOW", [], []), role="ROLE_TECHNICIAN")
        other = _trace(3, "who can approve", _scope("LOW", [], []), role="ROLE_USER")
        fixtures = [positive, negative]
        joined, skipped, mismatches = report.join_traces([admin, tech, other], fixtures)
        self.assertEqual(_ids(joined, fixtures), {"pos": _uuid(1), "neg": _uuid(2)})
        self.assertEqual(skipped["actorMismatch"], 1)
        self.assertEqual(mismatches[0]["turnId"], _uuid(3))

    def test_unique_query_asked_as_another_actor_is_not_joined(self):
        # A visibility fixture asked as an admin is no evidence that a technician cannot see the document.
        negative = _fixture("neg", "who can approve", [], forbidden=["admin.governance"], role="ROLE_TECHNICIAN")
        admin = _trace(1, "who can approve", _scope("LOW", [_doc("admin.governance", "admin")], []), role="ROLE_ADMIN")
        joined, skipped, mismatches = report.join_traces([admin], [negative])
        self.assertEqual(joined, {})
        self.assertEqual(dict(skipped), {"actorMismatch": 1})
        self.assertEqual(mismatches, [{"turnId": _uuid(1), "role": "ROLE_ADMIN", "fixtureIds": ["neg"]}])

    def test_actor_proxy_joins_fixtures_that_expect_a_document(self):
        positive = _fixture("pos", "what is a vin", ["glossary.vin"], role="ROLE_TECHNICIAN")
        negative = _fixture("neg", "who can approve", [], forbidden=["admin.governance"], role="ROLE_TECHNICIAN")
        proxy = "ROLE_SYSTEM_ADMINISTRATOR"
        vin = _trace(1, "what is a vin", _scope("LOW", [], []), role=proxy)
        approve = _trace(2, "who can approve", _scope("LOW", [], []), role=proxy)
        stranger = _trace(3, "what is a vin", _scope("LOW", [], []), role="ROLE_USER")
        fixtures = [positive, negative]
        joined, skipped, mismatches = report.join_traces([vin, approve, stranger], fixtures, actor_proxy=proxy)
        self.assertEqual(_ids(joined, fixtures), {"pos": _uuid(1)})
        self.assertEqual(dict(skipped), {"proxyNoExpected": 1, "actorMismatch": 1})
        self.assertEqual(mismatches[0]["turnId"], _uuid(3))

    def test_actor_proxy_joins_no_fixture_without_actor_role(self):
        # Fail closed under the proxy too: a fixture that names no actor is evidence for no turn.
        fixture = _fixture("f", "q", ["d"])
        del fixture["actor"]
        trace = _trace(1, "q", _scope("LOW", [], []), role="ROLE_SYSTEM_ADMINISTRATOR")
        joined, skipped, _ = report.join_traces([trace], [fixture], actor_proxy="ROLE_SYSTEM_ADMINISTRATOR")
        self.assertEqual(joined, {})
        self.assertEqual(dict(skipped), {"actorMismatch": 1})

    def test_actor_proxy_prefers_a_role_match(self):
        own = _fixture("own", "q", ["d"], role="ROLE_SYSTEM_ADMINISTRATOR")
        other = _fixture("other", "q", ["d"], role="ROLE_TECHNICIAN")
        trace = _trace(1, "q", _scope("LOW", [], []), role="ROLE_SYSTEM_ADMINISTRATOR")
        joined, _, _ = report.join_traces([trace], [own, other], actor_proxy="ROLE_SYSTEM_ADMINISTRATOR")
        self.assertEqual(_ids(joined, [own, other]), {"own": _uuid(1)})

    def test_own_actor_turn_wins_over_a_proxy_turn_in_either_order(self):
        # "A role match still wins" holds per fixture, not per turn: a later proxy turn does not replace
        # the fixture's own actor's turn, and an own-actor turn replaces an earlier or later proxy turn.
        proxy = "ROLE_SYSTEM_ADMINISTRATOR"
        fixture = _fixture("f", "q", ["d"], role="ROLE_TECHNICIAN")
        own = _trace(1, "q", _scope("LOW", [], []), role="ROLE_TECHNICIAN")
        later_proxy = _trace(2, "q", _scope("LOW", [], []), role=proxy)
        later_proxy["startedAt"] = "2026-10-02T11:00:00Z"
        newer_proxy = _trace(3, "q", _scope("LOW", [], []), role=proxy)
        newer_proxy["startedAt"] = "2026-10-02T11:30:00Z"
        for turns in ([own, later_proxy], [newer_proxy, own], [newer_proxy, own, later_proxy]):
            joined, skipped, _ = report.join_traces(turns, [fixture], actor_proxy=proxy)
            self.assertEqual(_ids(joined, [fixture]), {"f": _uuid(1)})
            self.assertEqual(dict(skipped), {"duplicateTurn": len(turns) - 1})

    def test_a_proxy_turn_cannot_hide_an_own_actor_enforce_leak(self):
        proxy = "ROLE_SYSTEM_ADMINISTRATOR"
        fixture = _fixture("f", "q", ["d"], forbidden=["secret"], role="ROLE_TECHNICIAN")
        own = _trace(1, "q", _scope("LOW", [_doc("d")], []), role="ROLE_TECHNICIAN")
        later_proxy = _trace(2, "q", _scope("LOW", [_doc("d")], []), role=proxy)
        later_proxy["startedAt"] = "2026-10-02T11:00:00Z"
        leak = _enforce(own, [_doc("d"), _doc("secret", "admin")])
        result = report.build_report([own, later_proxy], [fixture], enforce_traces=[leak], actor_proxy=proxy)
        self.assertEqual(result["proxiedSamples"], 0)
        self.assertEqual(result["gate"]["verdict"], "FAIL")

    def test_own_actor_shadow_against_a_proxy_enforce_turn_is_an_invalid_pair(self):
        proxy = "ROLE_SYSTEM_ADMINISTRATOR"
        fixture = _fixture("f", "q", ["d"], role="ROLE_TECHNICIAN")
        own = _trace(1, "q", _scope("LOW", [_doc("d")], []), role="ROLE_TECHNICIAN")
        proxied = _trace(2, "q", _scope("LOW", [_doc("d")], []), role=proxy)
        result = report.build_report([own], [fixture], enforce_traces=[_enforce(proxied, [_doc("d")])],
                                     actor_proxy=proxy)
        self.assertEqual(result["roleMismatches"], 1)
        self.assertEqual(result["gate"]["verdict"], "INVALID_PAIR")
        self.assertIn("1 pair(s) were asked as another role", report.render_text(result))

    def test_own_actor_shadow_against_a_proxy_baseline_turn_is_nondeterministic(self):
        proxy = "ROLE_SYSTEM_ADMINISTRATOR"
        fixture = _fixture("f", "q", ["d"], role="ROLE_TECHNICIAN")
        own = _trace(1, "q", _scope("LOW", [_doc("d")], []), role="ROLE_TECHNICIAN")
        proxied = _trace(2, "q", _scope("LOW", [_doc("d")], []), role=proxy)
        result = report.build_report([own], [fixture], baseline_traces=[proxied], actor_proxy=proxy, verbose=True)
        self.assertEqual(result["nondeterministicList"][0]["differs"], ["role"])
        self.assertEqual(result["gate"]["verdict"], "NONDETERMINISTIC")

    def test_actor_proxy_ignores_the_forbidden_list(self):
        fixture = _fixture("f", "q", ["d"], forbidden=["secret"], role="ROLE_TECHNICIAN")
        trace = _trace(1, "q", _scope("LOW", [_doc("d", "order"), _doc("secret", "admin")], []),
                       role="ROLE_SYSTEM_ADMINISTRATOR")
        result = report.build_report([trace], [fixture], actor_proxy="ROLE_SYSTEM_ADMINISTRATOR")
        self.assertEqual(result["samples"], 1)
        self.assertEqual(result["proxiedSamples"], 1)
        self.assertEqual(result["overall"]["today"]["forbiddenHits"], 0)
        self.assertIn("Actor proxy ROLE_SYSTEM_ADMINISTRATOR: 1 sample(s)", report.render_text(result))

    def test_simple_chat_fixture_is_exempt_not_missing(self):
        # The simple-chat path resolves no scope and retrieves nothing: the rag consumer never acts on it.
        scoped = _fixture("scoped", "where is WO-1234", ["workorder.guide"])
        chatty = _fixture("chatty", "how do orders work", ["order.guide"])
        hit = _trace(1, "where is WO-1234", _scope("HIGH", [_doc("workorder.guide")], ["workorder.guide"]))
        simple = _trace(2, "how do orders work", None)
        simple["simpleChat"] = True
        result = report.build_report([hit, simple], [scoped, chatty], verbose=True)
        self.assertEqual(result["fixturesWithoutTrace"], 0)
        self.assertEqual(result["fixturesExempt"], {"simpleChat": 1})
        self.assertEqual(result["fixturesExemptIds"], {"simpleChat": ["chatty"]})
        self.assertNotEqual(result["gate"]["verdict"], "INCOMPLETE")
        self.assertIn("exempt (simpleChat): chatty", report.render_text(result))

    def test_proxy_untestable_visibility_fixture_is_exempt(self):
        positive = _fixture("pos", "who can approve", ["admin.governance"], role="ROLE_ADMIN")
        negative = _fixture("neg", "who can approve", [], forbidden=["admin.governance"], role="ROLE_TECHNICIAN")
        proxy = "ROLE_SYSTEM_ADMINISTRATOR"
        turn = _trace(1, "who can approve", _scope("LOW", [_doc("admin.governance", "admin")], []), role=proxy)
        result = report.build_report([turn], [positive, negative], actor_proxy=proxy)
        self.assertEqual(result["fixturesJoined"], 1)
        self.assertEqual(result["fixturesWithoutTrace"], 0)
        self.assertEqual(result["fixturesExempt"], {"proxyUntestable": 1})
        text = report.render_text(result)
        self.assertIn("proxyUntestable=1 (another actor's visibility check", text)
        self.assertNotIn("simple-chat", text)

    def test_visibility_fixture_without_actor_role_is_not_exempt_under_the_proxy(self):
        negative = _fixture("neg", "who can approve", [], forbidden=["admin.governance"])
        del negative["actor"]
        result = report.build_report([], [negative], actor_proxy="ROLE_SYSTEM_ADMINISTRATOR")
        self.assertEqual(result["fixturesWithoutTrace"], 1)
        self.assertEqual(result["fixturesExempt"], {})

    def test_a_fixture_one_run_joined_is_exempt_from_no_run(self):
        # The technician's visibility fixture joined the shadow run by its own role, so it is testable:
        # a missing enforce turn is missing evidence, not a proxyUntestable exemption.
        positive = _fixture("pos", "what is a vin", ["glossary.vin"], role="ROLE_TECHNICIAN")
        negative = _fixture("neg", "who can approve", [], forbidden=["admin.governance"], role="ROLE_TECHNICIAN")
        vin = _trace(1, "what is a vin", _scope("LOW", [_doc("glossary.vin", "master")], []), role="ROLE_TECHNICIAN")
        approve = _trace(2, "who can approve", _scope("LOW", [], []), role="ROLE_TECHNICIAN")
        result = report.build_report([vin, approve], [positive, negative], verbose=True,
                                     enforce_traces=[_enforce(vin, vin["scope"]["retrievedDocuments"])],
                                     actor_proxy="ROLE_SYSTEM_ADMINISTRATOR")
        self.assertEqual(result["fixturesExempt"], {})
        self.assertEqual(result["fixturesWithoutEnforceTraceIds"], ["neg"])
        self.assertEqual(result["gate"]["verdict"], "INCOMPLETE")

    def test_simple_chat_in_one_run_does_not_excuse_a_fixture_another_run_joined(self):
        fixture = _fixture("f", "how do orders work", ["order.guide"])
        shadow = _trace(1, "how do orders work", _scope("LOW", [_doc("order.guide")], []))
        simple = _trace(2, "how do orders work", None)
        simple["simpleChat"] = True
        result = report.build_report([shadow], [fixture], enforce_traces=[simple])
        self.assertEqual(result["fixturesExempt"], {})
        self.assertEqual(result["fixturesWithoutEnforceTrace"], 1)
        self.assertEqual(result["gate"]["verdict"], "INCOMPLETE")

    def test_a_scopeless_turn_that_is_not_simple_chat_exempts_nothing(self):
        fixture = _fixture("f", "how do orders work", ["order.guide"])
        broken = _trace(1, "how do orders work", None)
        result = report.build_report([broken], [fixture])
        self.assertEqual(result["fixturesExempt"], {})
        self.assertEqual(result["fixturesWithoutTrace"], 1)

    def test_without_proxy_a_visibility_fixture_still_needs_its_trace(self):
        negative = _fixture("neg", "who can approve", [], forbidden=["admin.governance"], role="ROLE_TECHNICIAN")
        result = report.build_report([], [negative])
        self.assertEqual(result["fixturesWithoutTrace"], 1)
        self.assertEqual(result["fixturesExempt"], {})

    def _tool_pair(self, shadow_tags, enforce_tags, **kwargs):
        # A tags dict of None: the turn carries no tagging record (AlphaEvalTurnTraceRecorder writes null).
        fixture = _fixture("f", "q", ["d"])
        shadow = _trace(1, "q", _scope("HIGH", [_doc("d")], ["d"]))
        enforce = _trace(2, "q", _scope("HIGH", [_doc("d")], ["d"]))
        enforce["scope"]["mode"] = "ENFORCE"
        enforce["scope"]["enforced"] = ["RAG"]
        shadow["selectedTools"], enforce["selectedTools"] = ["A", "B"], ["A"]
        for trace, tags in ((shadow, shadow_tags), (enforce, enforce_tags)):
            trace["tags"] = None if tags is None else {
                "tags": [{"name": n, "actingValue": v} for n, v in tags.items()]}
        return report.build_report([shadow], [fixture], enforce_traces=[enforce], verbose=True, **kwargs)

    def test_a_tag_that_acted_in_neither_turn_is_no_drift(self):
        # A null actingValue (no answer acted) and an entry no tagger answered both mean consumers read
        # nothing: the tool change counts.
        result = self._tool_pair({"workflow_state": "IDLE", "entity_work-order": None}, {"workflow_state": "IDLE"})
        self.assertEqual(result["toolChangeList"][0]["tagDrift"], [])
        self.assertEqual(result["toolSelectionChanges"], 1)
        self.assertEqual(result["gate"]["verdict"], "FAIL")

    def test_a_turn_without_a_tagging_record_shows_no_drift(self):
        result = self._tool_pair({"workflow_state": "IDLE"}, None)
        self.assertEqual(result["toolChangeList"][0]["tagDrift"], [])
        self.assertEqual(result["toolSelectionChanges"], 1)
        self.assertEqual(result["gate"]["verdict"], "FAIL")

    def test_tool_change_under_the_same_tags_fails(self):
        result = self._tool_pair({"workflow_state": "IDLE"}, {"workflow_state": "IDLE"})
        self.assertEqual(result["gate"]["verdict"], "FAIL")
        self.assertEqual(result["toolSelectionChanges"], 1)
        self.assertEqual(result["tagDriftToolChanges"], 0)

    def test_tool_change_under_drifted_tags_is_listed_not_counted(self):
        # One pair of one: let the cap allow every pair, the cap has its own tests.
        result = self._tool_pair({"workflow_state": "IDLE"}, {"workflow_state": "RECEIVING_ASN"}, max_tag_drift=1.0)
        self.assertEqual(result["gate"]["verdict"], "PASS")
        self.assertEqual(result["toolSelectionChanges"], 0)
        self.assertEqual(result["tagDriftToolChanges"], 1)
        self.assertEqual(result["toolChangeList"][0]["tagDrift"], ["workflow_state"])
        self.assertIn("tagDrift=['workflow_state']", report.render_text(result))

    def _drift_run(self, pairs, drifted, **kwargs):
        fixtures, shadow, enforce = [], [], []
        for n in range(pairs):
            fixtures.append(_fixture(f"f{n}", f"q{n}", ["d"]))
            turn = _trace(n + 1, f"q{n}", _scope("HIGH", [_doc("d")], ["d"]))
            turn["tags"] = {"tags": [{"name": "workflow_state", "actingValue": "IDLE"}]}
            other = _enforce(turn, [_doc("d")])
            if n < drifted:
                turn["selectedTools"] = ["A", "B"]
                other["selectedTools"] = ["A"]
                other["tags"] = {"tags": [{"name": "workflow_state", "actingValue": "RECEIVING_ASN"}]}
            shadow.append(turn)
            enforce.append(other)
        return report.build_report(shadow, fixtures, enforce_traces=enforce, **kwargs)

    def test_tag_drift_up_to_the_cap_is_excused(self):
        result = self._drift_run(10, 1)
        self.assertEqual(result["tagDriftToolChanges"], 1)
        self.assertEqual(result["gate"]["verdict"], "PASS")

    def test_tag_drift_over_the_cap_is_incomplete(self):
        result = self._drift_run(10, 2)
        self.assertEqual(result["gate"]["verdict"], "INCOMPLETE")
        self.assertIn("drifted tags in 2 of 10 pair(s), over the 10% cap", result["gate"]["reasons"][0])
        self.assertEqual(self._drift_run(10, 2, max_tag_drift=0.2)["gate"]["verdict"], "PASS")

    def test_an_all_proxied_pass_says_the_forbidden_criterion_was_not_exercised(self):
        proxy = "ROLE_SYSTEM_ADMINISTRATOR"
        fixture = _fixture("f", "q", ["d"], forbidden=["secret"], role="ROLE_TECHNICIAN")
        turn = _trace(1, "q", _scope("LOW", [_doc("d")], []), role=proxy)
        result = report.build_report([turn], [fixture], enforce_traces=[_enforce(turn, [_doc("d")])],
                                     actor_proxy=proxy)
        self.assertEqual(result["gate"]["verdict"], "PASS")
        self.assertEqual((result["forbiddenListsScored"], result["forbiddenListsIgnored"]), (0, 1))
        self.assertFalse(result["forbiddenCriterionExercised"])
        self.assertIn("forbidden-document criterion not exercised", result["gate"]["reasons"][0])
        self.assertIn("the forbidden-document criterion was not exercised", report.render_text(result))

    def test_one_own_actor_forbidden_list_exercises_the_criterion(self):
        proxy = "ROLE_SYSTEM_ADMINISTRATOR"
        proxied = _fixture("p", "q", ["d"], forbidden=["secret"], role="ROLE_TECHNICIAN")
        own = _fixture("o", "r", ["d"], forbidden=["secret"], role=proxy)
        turns = [_trace(1, "q", _scope("LOW", [_doc("d")], []), role=proxy),
                 _trace(2, "r", _scope("LOW", [_doc("d")], []), role=proxy)]
        result = report.build_report(turns, [proxied, own], enforce_traces=[_enforce(t, [_doc("d")]) for t in turns],
                                     actor_proxy=proxy)
        self.assertEqual((result["forbiddenListsScored"], result["forbiddenListsIgnored"]), (1, 1))
        self.assertTrue(result["forbiddenCriterionExercised"])
        self.assertEqual(result["gate"]["reasons"], [])

    def test_fixture_without_actor_role_joins_no_turn(self):
        # Fail closed: a fixture that names no actor cannot say which turn is evidence for it.
        fixture = _fixture("f", "q", ["d"])
        del fixture["actor"]
        joined, skipped, _ = report.join_traces([_trace(1, "q", _scope("LOW", [], []), role="ROLE_USER")], [fixture])
        self.assertEqual(joined, {})
        self.assertEqual(skipped["actorMismatch"], 1)

    def test_one_turn_scores_every_fixture_of_its_question_and_actor(self):
        # rag-retrieval has two technician fixtures for "who can approve a permission change" with
        # different forbidden lists: one turn is evidence for both, so neither is left without a trace.
        a = _fixture("a", "who can approve", [], forbidden=["admin.governance"], role="ROLE_TECHNICIAN")
        c = _fixture("c", "who can approve", [], forbidden=["admin.governance", "security.matrix"],
                     role="ROLE_TECHNICIAN")
        tech = _trace(1, "who can approve", _scope("LOW", [], []), role="ROLE_TECHNICIAN")
        joined, skipped, _ = report.join_traces([tech], [a, c])
        self.assertEqual(_ids(joined, [a, c]), {"a": _uuid(1), "c": _uuid(1)})
        self.assertEqual(dict(skipped), {})

    def test_a_rerun_turn_replaces_the_older_attempt(self):
        fixture = _fixture("f", "q", ["d"])
        rerun, old = _trace(1, "q", _scope("LOW", [], [])), _trace(2, "q", _scope("LOW", [], []))
        rerun["startedAt"] = "2026-10-02T12:00:00Z"
        # Glob order puts the older attempt's export first; the later startedAt still wins.
        joined, skipped, _ = report.join_traces([old, rerun], [fixture])
        self.assertEqual(_ids(joined, [fixture]), {"f": _uuid(1)})
        self.assertEqual(skipped["duplicateTurn"], 1)
        joined, _, _ = report.join_traces([rerun, old], [fixture])
        self.assertEqual(_ids(joined, [fixture]), {"f": _uuid(1)})

    def test_unmatched_trace_is_counted(self):
        joined, skipped, _ = report.join_traces([_trace(1, "nothing like this", _scope("LOW", [], []))],
                                                [_fixture("f", "q", ["d"])])
        self.assertEqual(joined, {})
        self.assertEqual(skipped["noFixture"], 1)


class BuildReportTest(unittest.TestCase):
    def setUp(self):
        self.report = report.build_report([t for t, _ in ALL], [f for _, f in ALL], verbose=True,
                                          enforce_traces=ENFORCE_ALL)

    def test_sample_accounting(self):
        self.assertEqual(self.report["samples"], 5)
        self.assertEqual(self.report["fixturesJoined"], 5)
        self.assertEqual(self.report["fixturesWithoutTrace"], 0)
        self.assertEqual(self.report["fixturesEnforceJoined"], 5)
        self.assertEqual(self.report["fixturesWithoutEnforceTrace"], 0)
        self.assertEqual(self.report["graphHashes"], {"c878c7206d2ed660": 10})
        self.assertEqual(self.report["enforcedConsumers"], {"RAG": 5})
        self.assertEqual(self.report["overall"]["paired"], 5)

    def test_today_metrics(self):
        today = self.report["overall"]["today"]
        # Four positive fixtures, all hit today: kept at rank 2, dropped at rank 1, rescued at rank 2, low at rank 1.
        self.assertEqual(today["hitAtK"], 1.0)
        self.assertAlmostEqual(today["mrr"], (0.5 + 1.0 + 0.5 + 1.0) / 4)
        self.assertEqual(today["recallAtK"], 1.0)
        self.assertEqual(today["forbiddenHits"], 1)

    def test_enforce_metrics_and_fail_verdict(self):
        enforce = self.report["overall"]["enforce"]
        # The dropped hit is gone; the rescued one is at rank 1; the LOW turns are untouched.
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

    def test_simulated_preview_metrics(self):
        simulated = self.report["overall"]["simulated"]
        self.assertEqual(simulated["hitAtK"], 0.75)
        self.assertAlmostEqual(simulated["mrr"], (0.5 + 0.0 + 1.0 + 1.0) / 4)
        self.assertEqual(self.report["gate"]["simulated"]["verdict"], "FAIL")

    def test_loss_and_regression_lists_name_the_document(self):
        self.assertEqual(self.report["losses"], 1)
        [loss] = self.report["lossList"]
        self.assertEqual(loss["fixtureId"], "dropped-hit")
        self.assertEqual(loss["lost"], ["glossary.identifiers"])
        self.assertEqual(loss["enforce"], ["workorder.public"])
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
        self.assertEqual(high["lost"], 1)
        low = self.report["byConfidence"]["LOW"]
        self.assertEqual(low["today"], low["enforce"])
        self.assertEqual(low["today"], low["simulated"])

    def test_forbidden_list(self):
        [entry] = self.report["forbiddenList"]
        self.assertEqual(entry["fixtureId"], "forbidden-neg")
        self.assertEqual(entry["today"], ["admin.governance"])
        self.assertEqual(entry["simulated"], ["admin.governance"])
        self.assertEqual(entry["enforce"], ["admin.governance"])


class VerdictTest(unittest.TestCase):
    def _build(self, cases=PASSING, enforce=ENFORCE_PASSING, **kwargs):
        return report.build_report([t for t, _ in cases], [f for _, f in cases], enforce_traces=enforce, **kwargs)

    def test_pass_when_the_enforce_run_regresses_nothing(self):
        result = self._build()
        self.assertEqual(result["gate"]["verdict"], "PASS")
        self.assertEqual(result["gate"]["reasons"], [])
        self.assertEqual(result["gate"]["failingSets"], [])
        # MRR improved: the rescued document moved from rank 2 to rank 1.
        self.assertGreater(result["gate"]["overall"]["deltas"]["mrr"], 0)
        self.assertNotIn("lossList", result)

    def test_shadow_alone_never_passes(self):
        # The simulation finds nothing to drop, but it cannot see the all-scope pool: no verdict.
        result = self._build(enforce=None)
        self.assertEqual(result["gate"]["verdict"], "NO_ENFORCE_RUN")
        self.assertEqual(result["gate"]["simulated"]["verdict"], "PASS")
        self.assertIsNone(result["gate"]["overall"])
        self.assertIsNone(result["fixturesWithoutEnforceTrace"])

    def test_all_scope_candidate_displacing_a_hit_fails_though_the_simulation_passes(self):
        # Under enforce the retrievers span every scope: an in-scope document of another domain, never
        # in the shadow pool, enters the fusion and outranks the hit. The replay cannot see it.
        shadow = _trace(1, "refund rules", _scope("HIGH", [_doc("order.returns-refunds", "order")],
                                                  ["order.returns-refunds", "billing.refund-policy"]))
        fixture = _fixture("displaced", "refund rules", ["order.returns-refunds"])
        enforce = _enforce(shadow, [_doc("billing.refund-policy", "billing"), _doc("order.returns-refunds", "order")])
        result = report.build_report([shadow], [fixture], enforce_traces=[enforce], verbose=True)
        self.assertEqual(result["gate"]["simulated"]["verdict"], "PASS")
        self.assertEqual(result["gate"]["verdict"], "FAIL")
        self.assertEqual(result["gate"]["overall"]["deltas"]["mrr"], -0.5)

    def test_candidate_promoted_from_below_the_cut_counts_in_the_enforce_run(self):
        shadow = _trace(1, "refund rules", _scope("HIGH", [_doc("billing.x", "billing")], ["order.returns-refunds"]))
        fixture = _fixture("promoted", "refund rules", ["order.returns-refunds"])
        enforce = _enforce(shadow, [_doc("order.returns-refunds", "order")])
        result = report.build_report([shadow, KEPT_HIT[0]], [fixture, KEPT_HIT[1]],
                                     enforce_traces=[enforce, ENFORCE_ALL[0]])
        self.assertEqual(result["overall"]["today"]["hitAtK"], 0.5)
        self.assertEqual(result["overall"]["enforce"]["hitAtK"], 1.0)
        self.assertEqual(result["gate"]["verdict"], "PASS")

    def test_a_new_leak_hidden_behind_a_fixed_one_fails(self):
        # Totals 1 -> 1, but fixture b now surfaces a document it must not see.
        a = _fixture("a", "leak a", [], forbidden=["x.doc"])
        b = _fixture("b", "leak b", [], forbidden=["y.doc"])
        ta = _trace(11, "leak a", _scope("HIGH", [_doc("x.doc")], []))
        tb = _trace(12, "leak b", _scope("HIGH", [], []))
        result = report.build_report([KEPT_HIT[0], ta, tb], [KEPT_HIT[1], a, b], verbose=True,
                                     enforce_traces=[ENFORCE_ALL[0], _enforce(ta, []), _enforce(tb, [_doc("y.doc")])])
        self.assertEqual(result["gate"]["overall"]["deltas"]["forbiddenHits"], 0)
        self.assertEqual(result["overall"]["newLeaks"], 1)
        self.assertEqual(result["gate"]["verdict"], "FAIL")
        self.assertIn("new forbidden hit in 1 sample(s)", result["gate"]["overall"]["failures"])
        self.assertEqual([e["new"] for e in result["forbiddenList"]], [[], ["y.doc"]])

    def test_a_fixture_regression_offset_in_the_means_fails(self):
        # rag-lexical MRR 1.0 -> 0.5, rag-retrieval 0.5 -> 1.0: every overall mean is unchanged, but
        # one fixture got worse, and that is what the gate is for.
        lex = _trace(21, "lex q", _scope("HIGH", [_doc("e1"), _doc("o1")], ["e1", "o1"]))
        ret = _trace(22, "ret q", _scope("HIGH", [_doc("o2"), _doc("e2")], ["e2", "o2"]))
        fixtures = [_fixture("lex", "lex q", ["e1"]), _fixture("ret", "ret q", ["e2"], set_name="rag-retrieval")]
        enforce = [_enforce(lex, [_doc("o1"), _doc("e1")]), _enforce(ret, [_doc("e2"), _doc("o2")])]
        result = report.build_report([lex, ret], fixtures, enforce_traces=enforce, verbose=True)
        deltas = result["gate"]["overall"]["deltas"]
        self.assertEqual((deltas["hitAtK"], deltas["mrr"], deltas["recallAtK"]), (0.0, 0.0, 0.0))
        self.assertEqual(result["regressedFixtures"], 1)
        self.assertIn("1 fixture(s) regressed (lost an expected document or MRR fell)",
                      result["gate"]["overall"]["failures"])
        self.assertEqual(result["gate"]["failingSets"], ["rag-lexical"])
        self.assertEqual(result["gate"]["failingConfidences"], ["HIGH"])
        self.assertEqual(result["gate"]["verdict"], "FAIL")
        [entry] = result["lossList"]
        self.assertEqual((entry["fixtureId"], entry["lost"], entry["mrr"]), ("lex", [], [1.0, 0.5]))

    def test_a_lost_document_fails_though_every_mean_improves(self):
        # Two expected documents; enforce keeps the first at rank 1 and loses the second: its MRR
        # holds and its recall@5 falls, while another fixture's gain lifts every mean.
        shadow = _trace(23, "two docs", _scope("HIGH", [_doc("e1"), _doc("e2")], ["e1", "e2"]))
        lose = _enforce(shadow, [_doc("e1"), _doc("o1")])
        gain_t = _trace(24, "other q", _scope("HIGH", [_doc("o3"), _doc("e3")], ["e3", "e4"]))
        gain = _enforce(gain_t, [_doc("e3"), _doc("e4"), _doc("e5")])
        fixtures = [_fixture("two", "two docs", ["e1", "e2"]), _fixture("other", "other q", ["e3", "e4", "e5"])]
        result = report.build_report([shadow, gain_t], fixtures, enforce_traces=[lose, gain])
        deltas = result["gate"]["overall"]["deltas"]
        self.assertTrue(all(deltas[m] >= 0 for m in ("hitAtK", "mrr", "recallAtK")), deltas)
        self.assertEqual(result["losses"], 1)
        self.assertEqual(result["gate"]["verdict"], "FAIL")

    def test_lost_reads_todays_top_k_only(self):
        # The expected document sits at rank 6 today: outside the top-5, so enforce cannot lose it.
        docs = [_doc(f"d{i}") for i in range(5)] + [_doc("e")]
        shadow = _trace(31, "deep q", _scope("LOW", docs, []))
        result = report.build_report([shadow, KEPT_HIT[0]], [_fixture("deep", "deep q", ["e"]), KEPT_HIT[1]],
                                     enforce_traces=[_enforce(shadow, docs[:5]), ENFORCE_ALL[0]], verbose=True)
        self.assertEqual(result["losses"], 0)

    def test_empty_retrieval_on_both_sides_is_no_data(self):
        # An empty or broken RAG store compares 0 with 0; that is no evidence.
        shadow = _trace(41, "q", _scope("HIGH", [], []))
        result = report.build_report([shadow], [_fixture("f", "q", ["d"])], enforce_traces=[_enforce(shadow, [])])
        self.assertEqual(result["gate"]["verdict"], "NO_DATA")
        self.assertIn("hit@k is 0", result["gate"]["reasons"][0])

    def test_a_stray_turn_from_another_graph_does_not_split_the_evidence(self):
        stray = _trace(51, "unrelated chat", _scope("LOW", [], []))
        stray["scope"]["graphHash"] = "0000aaaa"
        result = report.build_report([t for t, _ in PASSING] + [stray], [f for _, f in PASSING],
                                     enforce_traces=ENFORCE_PASSING)
        self.assertEqual(result["graphHashes"], {"c878c7206d2ed660": 8})
        self.assertEqual(result["gate"]["verdict"], "PASS")

    def test_confidence_mismatch_between_the_runs_is_an_invalid_pair(self):
        enforce = [json.loads(json.dumps(ENFORCE_PASSING[0]))] + ENFORCE_PASSING[1:]
        enforce[0]["scope"]["confidence"] = "NONE"
        result = self._build(enforce=enforce)
        self.assertEqual(result["confidenceMismatches"], 1)
        self.assertEqual(result["gate"]["verdict"], "INVALID_PAIR")
        self.assertIn("1 pair(s) resolved a different scope confidence", report.render_text(result))

    def test_enforce_run_with_another_consumer_enforced_is_rejected(self):
        # With tools or card also enforced, more than the rag filter differs from the shadow run.
        both = _enforce(KEPT_HIT[0], ENFORCE_ALL[0]["scope"]["retrievedDocuments"], n=803, enforced=("RAG", "TOOLS"))
        result = self._build(enforce=[both] + ENFORCE_PASSING[1:])
        self.assertEqual(result["enforceSkipped"], {"wrongMode": 1})
        self.assertEqual(result["gate"]["verdict"], "INCOMPLETE")

    def test_changed_tool_selection_fails(self):
        # Section 9's tool criterion: under RAG-only enforcement every pair must be given today's tools.
        enforce = [json.loads(json.dumps(e)) for e in ENFORCE_PASSING]
        enforce[0]["selectedTools"] = ["OrderFacadeTool"]
        enforce[1]["offeredTools"] = [{"name": "inventory_getstock", "description": "d", "inputSchema": "{}"}]
        result = self._build(enforce=enforce, verbose=True)
        self.assertEqual(result["toolSelectionChanges"], 2)
        self.assertEqual(result["gate"]["verdict"], "FAIL")
        self.assertIn("tool selection changed in 2 pair(s)", result["gate"]["reasons"])
        self.assertEqual([e["changed"] for e in result["toolChangeList"]], [["selectedTools"], ["offeredTools"]])

    def test_tool_selection_compares_names_not_order_or_descriptions(self):
        shadow = [json.loads(json.dumps(t)) for t, _ in PASSING]
        enforce = [json.loads(json.dumps(e)) for e in ENFORCE_PASSING]
        shadow[0]["selectedTools"] = ["a", "b"]
        shadow[0]["offeredTools"] = [{"name": "a", "description": "x", "inputSchema": "{}"}]
        enforce[0]["selectedTools"] = ["b", "a"]
        enforce[0]["offeredTools"] = [{"name": "a", "description": "y", "inputSchema": "{}"}]
        result = report.build_report(shadow, [f for _, f in PASSING], enforce_traces=enforce)
        self.assertEqual(result["toolSelectionChanges"], 0)
        self.assertEqual(result["gate"]["verdict"], "PASS")



    def test_forbidden_increase_fails_with_rank_metrics_held(self):
        trace, fixture = KEPT_HIT
        negative = _fixture("neg", "who sees payroll", [], forbidden=["people.payroll"])
        neg_trace = _trace(7, "who sees payroll", _scope("HIGH", [], ["people.payroll"]))
        enforce = [ENFORCE_ALL[0], _enforce(neg_trace, [_doc("people.payroll", "people")])]
        result = report.build_report([trace, neg_trace], [fixture, negative], enforce_traces=enforce)
        self.assertEqual(result["gate"]["verdict"], "FAIL")
        self.assertEqual(result["gate"]["overall"]["deltas"]["forbiddenHits"], 1)

    def test_fixture_without_a_shadow_trace_is_incomplete(self):
        cases = PASSING + [DROPPED_HIT]
        result = report.build_report([t for t, _ in PASSING], [f for _, f in cases], enforce_traces=ENFORCE_PASSING,
                                     verbose=True)
        self.assertEqual(result["gate"]["verdict"], "INCOMPLETE")
        self.assertEqual(result["fixturesWithoutTraceIds"], ["dropped-hit"])
        self.assertIn("1 fixture(s) without a shadow trace", result["gate"]["reasons"])

    def test_fixture_without_an_enforce_trace_is_incomplete(self):
        result = self._build(enforce=ENFORCE_PASSING[1:], verbose=True)
        self.assertEqual(result["gate"]["verdict"], "INCOMPLETE")
        self.assertEqual(result["fixturesWithoutEnforceTraceIds"], ["kept-hit"])
        self.assertEqual(result["overall"]["paired"], 3)
        self.assertEqual(result["overall"]["todayPaired"]["hitAtK"], 1.0)

    def test_enforce_traces_in_the_shadow_input_are_rejected(self):
        # An ENFORCE turn is already filtered: replaying the rule over it compares the list with itself.
        mixed = report.build_report([t for t, _ in PASSING] + [ENFORCE_ALL[1]], [f for _, f in ALL],
                                    enforce_traces=ENFORCE_ALL)
        self.assertEqual(mixed["skipped"], {"wrongMode": 1})
        self.assertEqual(mixed["gate"]["verdict"], "INCOMPLETE")

    def test_enforce_input_needs_enforce_mode_with_rag_enforced(self):
        tools_only = _enforce(KEPT_HIT[0], [_doc("order.returns-refunds")], n=801, enforced=("TOOLS",))
        shadow_copy = json.loads(json.dumps(KEPT_HIT[0]))
        shadow_copy["turnId"] = _uuid(802)
        result = self._build(enforce=[tools_only, shadow_copy] + ENFORCE_PASSING[1:])
        self.assertEqual(result["enforceSkipped"], {"wrongMode": 2})
        self.assertEqual(result["gate"]["verdict"], "INCOMPLETE")

    def test_two_graph_snapshots_are_mixed_evidence(self):
        enforce = [_enforce(KEPT_HIT[0], [_doc("order.returns-refunds")], graph_hash="0000aaaa")] + ENFORCE_PASSING[1:]
        result = self._build(enforce=enforce)
        self.assertEqual(result["gate"]["verdict"], "MIXED_GRAPH")
        self.assertEqual(result["graphHashes"], {"0000aaaa": 1, "c878c7206d2ed660": 7})

    def test_negative_fixtures_alone_are_no_data(self):
        result = self._build(cases=[FORBIDDEN], enforce=[ENFORCE_ALL[4]])
        self.assertEqual(result["samples"], 1)
        self.assertEqual(result["overall"]["scored"], 0)
        self.assertEqual(result["gate"]["verdict"], "NO_DATA")

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


class BaselineTest(unittest.TestCase):
    """The A/A check: a second shadow run of the same fixtures must resolve every turn identically."""

    @staticmethod
    def _baseline(trace, n, **changes):
        copy = json.loads(json.dumps(trace))
        copy["turnId"] = _uuid(n)
        copy["scope"].update(changes.pop("scope", {}))
        copy.update(changes)
        return copy

    def _build(self, baseline, **kwargs):
        return report.build_report([t for t, _ in PASSING], [f for _, f in PASSING], enforce_traces=ENFORCE_PASSING,
                                   baseline_traces=baseline, **kwargs)

    def test_identical_baseline_passes(self):
        baseline = [self._baseline(t, 700 + i) for i, (t, _) in enumerate(PASSING)]
        result = self._build(baseline)
        self.assertEqual(result["nondeterministicFixtures"], 0)
        self.assertEqual(result["gate"]["verdict"], "PASS")

    def test_any_difference_is_nondeterministic(self):
        baseline = [self._baseline(t, 700 + i) for i, (t, _) in enumerate(PASSING)]
        baseline[0]["scope"]["retrievedDocuments"] = list(reversed(baseline[0]["scope"]["retrievedDocuments"]))
        baseline[1]["scope"]["confidence"] = "LOW"
        baseline[2]["selectedTools"] = ["x"]
        result = self._build(baseline, verbose=True)
        self.assertEqual(result["nondeterministicFixtures"], 3)
        self.assertEqual(result["gate"]["verdict"], "NONDETERMINISTIC")
        self.assertEqual([e["differs"] for e in result["nondeterministicList"]],
                         [["retrievedDocuments"], ["confidence"], ["selectedTools"]])

    def test_nondeterminism_is_reported_without_an_enforce_run(self):
        baseline = [self._baseline(t, 700 + i) for i, (t, _) in enumerate(PASSING)]
        baseline[0]["scope"]["confidence"] = "NONE"
        result = report.build_report([t for t, _ in PASSING], [f for _, f in PASSING], baseline_traces=baseline)
        self.assertEqual(result["gate"]["verdict"], "NONDETERMINISTIC")

    def test_baseline_must_cover_every_fixture_and_be_shadow(self):
        baseline = [self._baseline(t, 700 + i) for i, (t, _) in enumerate(PASSING)]
        baseline[0]["scope"]["mode"] = "ENFORCE"
        result = self._build(baseline)
        self.assertEqual(result["baselineSkipped"], {"wrongMode": 1})
        self.assertEqual(result["fixturesWithoutBaselineTrace"], 1)
        self.assertEqual(result["gate"]["verdict"], "INCOMPLETE")
        self.assertIn("1 fixture(s) without a baseline trace", result["gate"]["reasons"])

    def test_baseline_from_another_graph_is_mixed(self):
        baseline = [self._baseline(t, 700 + i) for i, (t, _) in enumerate(PASSING)]
        baseline[0]["scope"]["graphHash"] = "0000aaaa"
        self.assertEqual(self._build(baseline)["gate"]["verdict"], "MIXED_GRAPH")


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

    @staticmethod
    def _write_ndjson(path, traces):
        with open(path, "w", encoding="utf-8") as handle:
            for trace in traces:
                handle.write(json.dumps(trace) + "\n")

    def test_cli_text_json_and_exit_codes(self):
        with tempfile.TemporaryDirectory() as tmp:
            os.makedirs(os.path.join(tmp, "rag-lexical"))
            traces_path = os.path.join(tmp, "traces.ndjson")
            enforce_path = os.path.join(tmp, "enforce.ndjson")
            self._write_ndjson(traces_path, [t for t, _ in ALL])
            self._write_ndjson(enforce_path, ENFORCE_ALL)
            fixture_path = os.path.join(tmp, "rag-lexical", "f.json")

            def write_fixtures(cases):
                with open(fixture_path, "w", encoding="utf-8") as handle:
                    json.dump({"fixtures": [{k: v for k, v in f.items() if k != "_set"} for _, f in cases]}, handle)

            def run(*argv):
                out = io.StringIO()
                with contextlib.redirect_stdout(out):
                    code = report.main(list(argv))
                return code, out.getvalue()

            write_fixtures(ALL)
            code, text = run("--file", traces_path, "--enforce-file", enforce_path, "--fixture", fixture_path,
                             "--verbose")
            self.assertEqual(code, 1)
            self.assertIn("GATE: FAIL", text)
            self.assertIn("regressed dropped-hit", text)
            self.assertIn("Tools (shadow metric only", text)

            lexicon_path = os.path.join(tmp, "entities.yaml")
            preload_path = os.path.join(tmp, "application.yml")
            with open(lexicon_path, "w", encoding="utf-8") as handle:
                handle.write(LEXICON_YML)
            with open(preload_path, "w", encoding="utf-8") as handle:
                handle.write(PRELOAD_YML)
            code, text = run("--file", traces_path, "--enforce-file", enforce_path, "--fixture", fixture_path,
                             "--json", "--lexicon", lexicon_path, "--preload", preload_path)
            self.assertEqual(code, 1)
            parsed = json.loads(text)
            self.assertEqual(parsed["gate"]["verdict"], "FAIL")
            self.assertEqual(parsed["coverage"]["lexiconEntities"], 3)
            self.assertEqual(parsed["coverage"]["entitiesWithNoDocument"], ["campaign"])

            # Dropping the failing turn but keeping its fixture is incomplete evidence, not a PASS.
            self._write_ndjson(traces_path, [t for t, _ in PASSING])
            self._write_ndjson(enforce_path, ENFORCE_PASSING)
            code, text = run("--file", traces_path, "--enforce-file", enforce_path, "--fixture", fixture_path)
            self.assertEqual(code, 1)
            self.assertIn("GATE: INCOMPLETE (1 fixture(s) without a shadow trace; "
                          "1 fixture(s) without an enforce trace)", text)

            # Every fixture joined on both sides and nothing regresses: PASS, exit code 0.
            write_fixtures(PASSING)
            code, text = run("--file", traces_path, "--enforce-file", enforce_path, "--fixture", fixture_path)
            self.assertEqual(code, 0)
            self.assertIn("GATE: PASS", text)

            # A second shadow run as the A/A baseline: identical turns keep the PASS.
            baseline_path = os.path.join(tmp, "baseline.ndjson")
            baseline = [json.loads(json.dumps(t)) for t, _ in PASSING]
            for i, trace in enumerate(baseline):
                trace["turnId"] = _uuid(700 + i)
            self._write_ndjson(baseline_path, baseline)
            code, text = run("--file", traces_path, "--enforce-file", enforce_path, "--baseline-file", baseline_path,
                             "--fixture", fixture_path)
            self.assertEqual(code, 0)
            self.assertIn("Baseline traces: 4; fixtures without a trace 0; nondeterministic 0", text)

            # The shadow run alone decides nothing, whatever the preview says.
            code, text = run("--file", traces_path, "--fixture", fixture_path)
            self.assertEqual(code, 1)
            self.assertIn("GATE: NO_ENFORCE_RUN", text)
            self.assertIn("Simulated preview (not evidence: it cannot see the all-scope candidate pool): PASS", text)


if __name__ == "__main__":
    unittest.main()
