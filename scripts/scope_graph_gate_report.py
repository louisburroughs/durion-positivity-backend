#!/usr/bin/env python3
"""Offline promotion gate for the scope graph's `rag` consumer (ADR-0069 section 9, spec 2.10/2.11).

ADR-0069 section 9 promotes a consumer on "a recorded gate run [that] shows, against the same run in
`shadow`, no regression". The report therefore reads two runs of the same RAG fixtures, made against
one graph snapshot:

  --file          the `shadow` run (scope.mode SHADOW): today's retrieval;
  --enforce-file  the run with the `rag` consumer in `enforce` (scope.mode ENFORCE, RAG in
                  scope.enforced): the filtered retrieval as production would serve it.

Each trace joins a RAG fixture by its `userMessage` and its `role`, and is scored from its
`scope.retrievedDocuments` (the final top-K handed to the model, in rank order, one entry per
document with its `rag_scope`):

  today      hit@k, MRR, recall@k and forbidden-document hits of the shadow top-K;
  enforce    the same four metrics of the enforce run's top-K (the real section 6 hook: retrievers
             over all scopes, the filter after fusion and before the cut);
  simulated  a preview from the shadow run alone: the section 6 rule replayed over the shadow top-K
             (on `HIGH` keep a document whose `documentId` is in `scope.scopeDocumentIds` or whose
             `ragScope` is `master`; on LOW / NONE keep everything; order preserved, nothing added).

The simulation is NOT evidence for promotion. Under enforce the retrievers are built over all scopes,
so an in-scope document of another domain, absent from the shadow candidate pool, can enter the
fusion and the reranker and displace a hit; and the hook filters the pool before the cut, so a
candidate below rank K can move up. The replay sees neither. It is a cheap first look (an expected
document it drops is out of scope and not master, so the real filter drops it too), and only the
enforce run decides.

The gate verdict, exit code 0 only on PASS:
  NO_DATA         no trace joined a fixture, no joined fixture expects a document (no rank metric), or
                  today's hit@k is 0 (an empty or broken RAG store would otherwise compare 0 with 0);
  MIXED_GRAPH     the joined traces come from more than one `graphHash`: the evidence is for no
                  single deployable snapshot;
  INCOMPLETE      a loaded fixture has no joined shadow trace, or (with --enforce-file) no joined
                  enforce trace: the evidence does not cover the fixture set;
  NO_ENFORCE_RUN  complete shadow evidence but no --enforce-file: the simulated preview is printed,
                  nothing is decided;
  FAIL            overall or within a fixture set (rag-lexical, rag-retrieval), the enforce hit@k, MRR
                  or recall@k fell below today's, the forbidden hits grew, or any fixture surfaced a
                  forbidden document it did not surface today (a total could hide it behind a fix);
  PASS            otherwise. The rank metrics are compared as means (section 9); the per-fixture
                  losses and the per-confidence blocks are printed for review, not gated.
Traces are filtered before the join: a `--file` trace not in SHADOW, or an `--enforce-file` trace not
in ENFORCE with RAG enforced, is skipped and counted (`wrongMode`); so are traces without a scope and
traces written before the identity lists existed (`retrievedDocuments` null, counts only).

The report also lists the fixtures whose expected document the enforce run lost from today's top-K
(and, as the preview's regression list, the ones the simulated filter dropped; ids under --verbose),
breaks the metrics down per confidence bucket (the shadow trace's) and per fixture set, and adds a
shadow-only tools section (the share of the model's tool calls that were inside the scope, per
confidence). The `tools` consumer is additive (it never removes a tool), so that section is a
diagnostic: its promotion needs its own gate.

Documentation coverage (every run, over the --file traces): per seed entity
(`scope.seeds[].entity`), the turns it seeded, how many of those resolved a scope with zero documents,
and the mean scope document count. With `--lexicon scope-graph/entities.yaml --preload
application.yml` the section adds, per lexicon entity, the static count of RAG documents annotated
with it (`mcp.rag.preload.docs[].entities`), so the list of entities with no document comes out of
every run; `--verbose` also prints the messages of the `NONE`-confidence turns (vocabulary the
lexicon missed; the messages are test fixtures). The two yml files are read by a minimal line reader
(the entries are flat `- key:` / `- id:` / `entities: [..]` lines), no yaml module needed.

A shadow trace whose `scopeDocumentIdsTruncated` is true cannot be replayed exactly (an in-scope
document beyond the cap looks out of scope); such samples are counted and flagged. The enforce run is
not affected: the hook reads the whole scope. A pair whose enforce turn resolved another confidence
than its shadow turn (same graph, different tags) is counted and flagged.

Not computed here: section 9's "tool selection hit rate at least equal". The `rag` consumer acts on
retrieval only, so with RAG the only enforced consumer the tool selection is today's by construction;
the enforced consumer sets of the enforce run are printed so a run with more is visible.

Input:
  --file PATH          JSON array or NDJSON of EvalTurnTrace objects from the shadow run ('-' reads
                       stdin). Repeatable; a turn present in more than one export (same turnId) is
                       kept once.
  --enforce-file PATH  The same, from the enforce run. Repeatable.
  --fixture PATH       RAG fixture file ({"fixtures":[{fixture_id, query, actor, expected:{doc_ids, k,
                       forbidden_doc_ids?}, rag_scope, tags}]}), from
                       pos-mcp-server/src/test/resources/eval/rag-lexical/ and eval/rag-retrieval/.
                       Repeatable. The fixture set is the file's parent directory name.

Join: a trace's candidates are the fixtures whose `query` equals its `userMessage` exactly, else
after trim + collapse whitespace + casefold (as scripts/tagging_shadow_report.py joins the tagging
gate). A candidate whose `actor.role` differs from the trace's `role`, or that names none, is not
joined (`actorMismatch`): a turn asked as another actor is no evidence for the fixture's visibility.
The trace is scored against every candidate left (two fixtures may ask one question as one actor
with different expectations). A fixture joined by more than one turn of a run keeps the latest by
`startedAt` (`duplicateTurn`), so a rerun's turn replaces an older attempt's.

Stdlib only, like scripts/tagging_shadow_report.py.
"""

import argparse
import json
import os
import re
import sys
from collections import Counter, defaultdict

DEFAULT_K = 5
MASTER = "master"
SHADOW = "SHADOW"
ENFORCE = "ENFORCE"
RAG = "RAG"
EPSILON = 1e-9
METRICS = ("hitAtK", "mrr", "recallAtK")


def parse_traces(text):
    """Accept a JSON array, a single object, or NDJSON."""
    text = text.strip()
    if not text:
        return []
    try:
        data = json.loads(text)
        return data if isinstance(data, list) else [data]
    except json.JSONDecodeError:
        return [json.loads(line) for line in text.splitlines() if line.strip()]


def merge_traces(batches):
    """Concatenate trace lists, keeping the first copy of each turnId (overlapping exports)."""
    merged, seen = [], set()
    for batch in batches:
        for trace in batch:
            turn = trace.get("turnId") if isinstance(trace, dict) else None
            key = ("turn", str(turn)) if turn is not None else ("json", json.dumps(trace, sort_keys=True))
            if key in seen:
                continue
            seen.add(key)
            merged.append(trace)
    return merged


def normalise_text(text):
    """Trim, collapse whitespace, casefold: the fallback join key."""
    return " ".join(str(text).split()).casefold()


def load_fixture_file(path, text):
    """The fixtures of one file, each stamped with its set (the parent directory name)."""
    data = json.loads(text)
    fixtures = data.get("fixtures", []) if isinstance(data, dict) else data
    set_name = os.path.basename(os.path.dirname(os.path.abspath(path))) or os.path.basename(path)
    out = []
    for fixture in fixtures:
        copy = dict(fixture)
        copy["_set"] = set_name
        out.append(copy)
    return out


def _is_master(rag_scope):
    """ScopeRagFilter.ragScopeOf: a chunk without a rag_scope is master for nobody."""
    return rag_scope is not None and str(rag_scope).strip().casefold() == MASTER


def simulate_enforce(scope):
    """The documents the ADR section 6 rule keeps, in order: the whole top-K unless confidence is HIGH."""
    retrieved = scope.get("retrievedDocuments") or []
    if str(scope.get("confidence") or "").upper() != "HIGH":
        return list(retrieved)
    in_scope = set(scope.get("scopeDocumentIds") or [])
    return [d for d in retrieved if d.get("documentId") in in_scope or _is_master(d.get("ragScope"))]


def score(documents, expected, forbidden, k):
    """hit@k, MRR, recall@k and forbidden hits of one ordered document list against one fixture.

    The three rank metrics are None when the fixture expects nothing (a visibility-negative fixture
    is scored on forbidden hits alone).
    """
    ids = [d.get("documentId") for d in documents]
    top = ids[:k]
    forbidden_hits = sorted(set(ids) & set(forbidden))
    if not expected:
        return {"hitAtK": None, "mrr": None, "recallAtK": None, "forbiddenHits": forbidden_hits}
    expected_set = set(expected)
    hit = 1.0 if any(doc in expected_set for doc in top) else 0.0
    mrr = 0.0
    for rank, doc in enumerate(ids, start=1):
        if doc in expected_set:
            mrr = 1.0 / rank
            break
    recall = len(expected_set & set(top)) / len(expected_set)
    return {"hitAtK": hit, "mrr": mrr, "recallAtK": recall, "forbiddenHits": forbidden_hits}


def join_traces(traces, fixtures):
    """Pair fixtures with the turns that asked them.

    Returns (joined, skipped, mismatches): joined maps a fixture's index in `fixtures` to its latest
    trace (by startedAt); skipped is a Counter of noUserMessage, noFixture, actorMismatch (the query
    matched, no candidate's actor role did; a fixture without one matches no turn) and duplicateTurn
    (a turn for an already joined fixture); mismatches lists the actor-mismatched turns.
    """
    exact, normalised = defaultdict(list), defaultdict(list)
    for index, fixture in enumerate(fixtures):
        query = fixture.get("query")
        if isinstance(query, str):
            exact[query].append(index)
            normalised[normalise_text(query)].append(index)
    joined, skipped, mismatches = {}, Counter(), []
    for trace in traces:
        message = trace.get("userMessage")
        if not isinstance(message, str):
            skipped["noUserMessage"] += 1
            continue
        candidates = exact.get(message) or normalised.get(normalise_text(message)) or []
        if not candidates:
            skipped["noFixture"] += 1
            continue
        role = trace.get("role")
        by_role = [i for i in candidates if _actor_role(fixtures[i]) == role]
        if not by_role:
            skipped["actorMismatch"] += 1
            mismatches.append({"turnId": trace.get("turnId"), "role": role,
                               "fixtureIds": [fixtures[i].get("fixture_id") for i in candidates]})
            continue
        duplicate = False
        for index in by_role:
            current = joined.get(index)
            if current is not None:
                duplicate = True
                # A rerun's turn replaces an older attempt's (ISO-8601 UTC sorts as text).
                if str(trace.get("startedAt") or "") <= str(current.get("startedAt") or ""):
                    continue
            joined[index] = trace
        if duplicate:
            skipped["duplicateTurn"] += 1
    return joined, skipped, mismatches


def _actor_role(fixture):
    actor = fixture.get("actor")
    return actor.get("role") if isinstance(actor, dict) else None


def _mean(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None


def _side(samples, side):
    """Means of the rank metrics and the forbidden totals of one side over a sample list."""
    block = {metric: _mean([s[side][metric] for s in samples]) for metric in METRICS}
    block["forbiddenHits"] = sum(len(s[side]["forbiddenHits"]) for s in samples)
    block["forbiddenSamples"] = sum(1 for s in samples if s[side]["forbiddenHits"])
    return block


def aggregate(samples):
    """Today and the simulated preview over every sample; today and enforce over the paired ones.

    `gate` compares the enforce run with today over the samples that have both (None when none do);
    `simulatedGate` compares the preview with today.
    """
    out = {"samples": len(samples), "scored": sum(1 for s in samples if s["today"]["hitAtK"] is not None)}
    out["today"] = _side(samples, "today")
    out["simulated"] = _side(samples, "simulated")
    out["dropped"] = sum(1 for s in samples if s["dropped"])
    out["kept"] = sum(s["keptCount"] for s in samples)
    out["retrieved"] = sum(s["retrievedCount"] for s in samples)
    out["simulatedGate"] = verdict(out["today"], out["simulated"])
    paired = [s for s in samples if s["enforce"] is not None]
    out["paired"] = len(paired)
    if paired:
        out["todayPaired"] = _side(paired, "today")
        out["enforce"] = _side(paired, "enforce")
        out["lost"] = sum(1 for s in paired if s["lost"])
        out["newLeaks"] = sum(1 for s in paired if s["newForbidden"])
        out["gate"] = verdict(out["todayPaired"], out["enforce"], out["newLeaks"])
    else:
        out["todayPaired"] = out["enforce"] = out["lost"] = out["newLeaks"] = out["gate"] = None
    return out


def verdict(today, enforce, new_leaks=0):
    """PASS when every rank metric held or improved, the forbidden hits did not grow and no sample
    gained a forbidden document it did not have today (`new_leaks`: a total can hide one leak behind
    another sample's fix); else FAIL + deltas."""
    deltas, failures = {}, []
    for metric in METRICS:
        before, after = today.get(metric), enforce.get(metric)
        if before is None or after is None:
            deltas[metric] = None
            continue
        deltas[metric] = after - before
        if after + EPSILON < before:
            failures.append(f"{metric} {before:.4f} -> {after:.4f}")
    deltas["forbiddenHits"] = enforce["forbiddenHits"] - today["forbiddenHits"]
    if enforce["forbiddenHits"] > today["forbiddenHits"]:
        failures.append(f"forbiddenHits {today['forbiddenHits']} -> {enforce['forbiddenHits']}")
    if new_leaks:
        failures.append(f"new forbidden hit in {new_leaks} sample(s)")
    return {"verdict": "FAIL" if failures else "PASS", "deltas": deltas, "failures": failures}


def _tool_names(trace):
    return [call.get("name") for call in trace.get("toolCalls") or [] if isinstance(call, dict)]


def _usable(traces, want_mode, skipped):
    """The traces with a scope recorded in `want_mode` (ENFORCE: with RAG enforced) and identity lists."""
    out = []
    for trace in traces:
        scope = trace.get("scope") if isinstance(trace, dict) else None
        if not isinstance(scope, dict):
            skipped["noScope"] += 1
            continue
        mode = str(scope.get("mode") or "").upper()
        enforced = {str(c).upper() for c in scope.get("enforced") or []}
        if mode != want_mode or (want_mode == ENFORCE and RAG not in enforced):
            skipped["wrongMode"] += 1
            continue
        if scope.get("retrievedDocuments") is None:
            skipped["noRetrievedDocuments"] += 1
            continue
        out.append(trace)
    return out


def _expected(fixture, k):
    expected_block = fixture.get("expected") or {}
    expected = list(expected_block.get("doc_ids") or [])
    forbidden = list(expected_block.get("forbidden_doc_ids") or fixture.get("forbidden_doc_ids") or [])
    return expected, forbidden, expected_block.get("k") or k


def _top_ids(documents, k):
    return {d.get("documentId") for d in documents[:k]}


def build_report(traces, fixtures, k=DEFAULT_K, verbose=False, lexicon_entities=None, preload_docs=None,
                 enforce_traces=None):
    """Aggregate the shadow traces, the enforce traces (None: no enforce run given) and the fixtures."""
    skipped, enforce_skipped = Counter(), Counter()
    shadow = _usable(traces, SHADOW, skipped)
    enforce = _usable(enforce_traces or [], ENFORCE, enforce_skipped)

    joined, join_skipped, mismatches = join_traces(shadow, fixtures)
    skipped.update(join_skipped)
    enforce_joined, enforce_join_skipped, enforce_mismatches = join_traces(enforce, fixtures)
    enforce_skipped.update(enforce_join_skipped)

    samples = []
    for index in sorted(joined):
        trace, fixture = joined[index], fixtures[index]
        scope = trace["scope"]
        expected, forbidden, fixture_k = _expected(fixture, k)
        retrieved = scope.get("retrievedDocuments") or []
        kept = simulate_enforce(scope)
        enforce_trace = enforce_joined.get(index)
        enforce_docs = enforce_trace["scope"].get("retrievedDocuments") or [] if enforce_trace else None
        today = score(retrieved, expected, forbidden, fixture_k)
        enforce_score = None if enforce_docs is None else score(enforce_docs, expected, forbidden, fixture_k)
        samples.append({
            "turnId": trace.get("turnId"),
            "enforceTurnId": enforce_trace.get("turnId") if enforce_trace else None,
            "fixtureId": fixture.get("fixture_id"),
            "set": fixture["_set"],
            "confidence": str(scope.get("confidence") or "UNKNOWN").upper(),
            "enforceConfidence": None if enforce_trace is None
            else str(enforce_trace["scope"].get("confidence") or "UNKNOWN").upper(),
            "scopeTruncated": bool(scope.get("scopeDocumentIdsTruncated")),
            "retrievedCount": len(retrieved),
            "keptCount": len(kept),
            "today": today,
            "simulated": score(kept, expected, forbidden, fixture_k),
            "enforce": enforce_score,
            # Forbidden documents the enforce run surfaced that today's run did not.
            "newForbidden": [] if enforce_score is None
            else sorted(set(enforce_score["forbiddenHits"]) - set(today["forbiddenHits"])),
            # The expected documents the simulated filter removed from the top-K: the preview's regressions.
            "dropped": sorted(set(expected) & {d.get("documentId") for d in retrieved}
                              - {d.get("documentId") for d in kept}),
            # The expected documents in today's top-k that the enforce run's top-k lost.
            "lost": None if enforce_docs is None else sorted(
                set(expected) & _top_ids(retrieved, fixture_k) - _top_ids(enforce_docs, fixture_k)),
            "retrievedIds": [d.get("documentId") for d in retrieved],
            "enforceIds": None if enforce_docs is None else [d.get("documentId") for d in enforce_docs],
            "scopeDocumentIds": list(scope.get("scopeDocumentIds") or []),
        })

    by_set = defaultdict(list)
    by_confidence = defaultdict(list)
    for sample in samples:
        by_set[sample["set"]].append(sample)
        by_confidence[sample["confidence"]].append(sample)

    overall = aggregate(samples)
    sets = {name: aggregate(group) for name, group in sorted(by_set.items())}
    confidences = {name: aggregate(group) for name, group in sorted(by_confidence.items())}

    fixture_ids = [f.get("fixture_id") for f in fixtures]
    without_trace = [fixture_ids[i] for i in range(len(fixtures)) if i not in joined]
    without_enforce = [fixture_ids[i] for i in range(len(fixtures)) if i not in enforce_joined]
    # The joined turns only: a stray turn that scores nothing does not split the evidence.
    scored_traces = {id(t): t for t in list(joined.values()) + list(enforce_joined.values())}.values()
    graph_hashes = Counter(str(t["scope"].get("graphHash")) for t in scored_traces)

    failing_sets = [name for name, block in sets.items() if block["gate"] and block["gate"]["verdict"] == "FAIL"]
    reasons = []
    if not samples or not overall["scored"]:
        gate_verdict = "NO_DATA"
        reasons.append("no joined fixture expects a document" if samples else "no trace joined a fixture")
    elif not overall["today"]["hitAtK"]:
        gate_verdict = "NO_DATA"
        reasons.append("today's hit@k is 0: the shadow run retrieved no expected document (empty or broken RAG store?)")
    elif len(graph_hashes) > 1:
        gate_verdict = "MIXED_GRAPH"
        reasons.append("graphHash " + ", ".join(f"{h}={n}" for h, n in sorted(graph_hashes.items())))
    elif without_trace or (enforce_traces is not None and without_enforce):
        gate_verdict = "INCOMPLETE"
        if without_trace:
            reasons.append(f"{len(without_trace)} fixture(s) without a shadow trace")
        if enforce_traces is not None and without_enforce:
            reasons.append(f"{len(without_enforce)} fixture(s) without an enforce trace")
    elif enforce_traces is None:
        gate_verdict = "NO_ENFORCE_RUN"
        reasons.append("no --enforce-file: the simulated preview decides nothing")
    elif overall["gate"]["verdict"] == "FAIL" or failing_sets:
        gate_verdict = "FAIL"
    else:
        gate_verdict = "PASS"

    report = {
        "k": k,
        "traces": len(traces),
        "enforceTraces": None if enforce_traces is None else len(enforce_traces),
        "skipped": dict(sorted(skipped.items())),
        "enforceSkipped": dict(sorted(enforce_skipped.items())),
        "graphHashes": dict(sorted(graph_hashes.items())),
        "enforcedConsumers": dict(sorted(Counter(
            ",".join(sorted(str(c).upper() for c in t["scope"].get("enforced") or [])) for t in enforce).items())),
        "samples": len(samples),
        "fixtures": len(fixtures),
        "fixturesJoined": len(joined),
        "fixturesWithoutTrace": len(without_trace),
        "fixturesEnforceJoined": len(enforce_joined),
        "fixturesWithoutEnforceTrace": None if enforce_traces is None else len(without_enforce),
        "scopeTruncatedSamples": sum(1 for s in samples if s["scopeTruncated"]),
        "confidenceMismatches": sum(1 for s in samples
                                    if s["enforceConfidence"] is not None and s["enforceConfidence"] != s["confidence"]),
        "overall": overall,
        "bySet": sets,
        "byConfidence": confidences,
        "gate": {
            "verdict": gate_verdict,
            "reasons": reasons,
            "overall": overall["gate"],
            "failingSets": failing_sets,
            "simulated": overall["simulatedGate"],
        },
        "tools": tools_section(shadow),
        "coverage": coverage_section(traces, lexicon_entities, preload_docs, verbose),
    }
    regressions = [s for s in samples if s["dropped"]]
    losses = [s for s in samples if s["lost"]]
    report["regressions"] = len(regressions)
    report["losses"] = len(losses)
    if verbose:
        report["lossList"] = [
            {"fixtureId": s["fixtureId"], "turnId": s["turnId"], "enforceTurnId": s["enforceTurnId"],
             "confidence": s["confidence"], "lost": s["lost"], "today": s["retrievedIds"], "enforce": s["enforceIds"]}
            for s in losses
        ]
        report["regressionList"] = [
            {"fixtureId": s["fixtureId"], "turnId": s["turnId"], "confidence": s["confidence"],
             "dropped": s["dropped"], "retrieved": s["retrievedIds"], "scopeDocumentIds": s["scopeDocumentIds"]}
            for s in regressions
        ]
        report["forbiddenList"] = [
            {"fixtureId": s["fixtureId"], "turnId": s["turnId"], "today": s["today"]["forbiddenHits"],
             "simulated": s["simulated"]["forbiddenHits"],
             "enforce": None if s["enforce"] is None else s["enforce"]["forbiddenHits"],
             "new": s["newForbidden"]}
            for s in samples
            if s["today"]["forbiddenHits"] or s["simulated"]["forbiddenHits"]
            or (s["enforce"] is not None and s["enforce"]["forbiddenHits"])
        ]
        report["fixturesWithoutTraceIds"] = without_trace
        if enforce_traces is not None:
            report["fixturesWithoutEnforceTraceIds"] = without_enforce
        report["actorMismatches"] = mismatches + enforce_mismatches
    return report


_LEXICON_ENTITY = re.compile(r"^  - key:\s*([^\s#]+)")
_PRELOAD_ID = re.compile(r"^\s*- id:\s*[\"']?([^\"'\s#]+)")
_PRELOAD_ENTITIES = re.compile(r"^\s*entities:\s*\[([^\]]*)\]")


def read_lexicon_entities(text):
    """The entity keys of scope-graph/entities.yaml: the `  - key:` items of its top-level `entities:` list.

    Nested `identifiers:` items also say `- key:` but sit deeper, so the match is anchored at the
    two-space indent of the entity list.
    """
    keys, in_entities = [], False
    for line in text.splitlines():
        if re.match(r"^entities:\s*(#.*)?$", line):
            in_entities = True
            continue
        if in_entities and re.match(r"^[A-Za-z_]", line):
            in_entities = False
        if in_entities:
            match = _LEXICON_ENTITY.match(line)
            if match:
                keys.append(match.group(1))
    return keys


def read_preload_docs(text):
    """{document id: [entity keys]} from the `mcp.rag.preload.docs` entries of an application yml.

    Each entry is a `- id:` line followed by its scalar lines, one of them `entities: [a, b]`
    (`[none]` for a platform-wide document, kept as no entity).
    """
    docs, current = {}, None
    for line in text.splitlines():
        match = _PRELOAD_ID.match(line)
        if match:
            current = match.group(1)
            docs.setdefault(current, [])
            continue
        match = _PRELOAD_ENTITIES.match(line)
        if match and current is not None:
            entities = [e.strip().strip("\"'") for e in match.group(1).split(",")]
            docs[current] = [e for e in entities if e and e != "none"]
            current = None
    return docs


def coverage_section(traces, lexicon_entities=None, preload_docs=None, verbose=False):
    """Documentation coverage: which seed entities resolve to scopes with no documents, and which
    lexicon entities no RAG document is annotated with.

    Uses every trace with a scope (counts-only traces included: `documentCount` is on all of them).
    """
    per_entity = defaultdict(lambda: {"turns": 0, "turnsWithNoDocument": 0, "documentCounts": [],
                                      "matchKinds": Counter()})
    none_messages = []
    scoped_turns = 0
    for trace in traces:
        scope = trace.get("scope") if isinstance(trace, dict) else None
        if not isinstance(scope, dict):
            continue
        scoped_turns += 1
        if str(scope.get("confidence") or "").upper() == "NONE":
            none_messages.append(trace.get("userMessage"))
        document_count = scope.get("documentCount")
        if document_count is None:
            document_count = len(scope.get("scopeDocumentIds") or [])
        seen = set()
        for seed in scope.get("seeds") or []:
            entity = seed.get("entity") if isinstance(seed, dict) else None
            if not entity or entity in seen:
                continue
            seen.add(entity)
            bucket = per_entity[entity]
            bucket["turns"] += 1
            bucket["turnsWithNoDocument"] += 1 if document_count == 0 else 0
            bucket["documentCounts"].append(document_count)
            bucket["matchKinds"][str(seed.get("matchKind") or "UNKNOWN")] += 1

    static_counts = None
    if preload_docs is not None:
        static_counts = Counter()
        for entities in preload_docs.values():
            for entity in entities:
                static_counts[entity] += 1

    entities = {}
    names = set(per_entity) | set(lexicon_entities or [])
    for name in sorted(names):
        bucket = per_entity.get(name)
        entry = {
            "turns": bucket["turns"] if bucket else 0,
            "turnsWithNoDocument": bucket["turnsWithNoDocument"] if bucket else 0,
            "meanScopeDocuments": _mean(bucket["documentCounts"]) if bucket else None,
            "matchKinds": dict(sorted(bucket["matchKinds"].items())) if bucket else {},
            "inLexicon": None if lexicon_entities is None else name in set(lexicon_entities),
            "staticDocuments": None if static_counts is None else static_counts.get(name, 0),
        }
        entities[name] = entry

    out = {
        "scopedTurns": scoped_turns,
        "noneConfidenceTurns": len(none_messages),
        "entities": entities,
        "entitiesWithNoDocument": sorted(
            name for name, e in entities.items()
            if (e["staticDocuments"] == 0) or (e["staticDocuments"] is None and e["turns"] and
                                               e["turnsWithNoDocument"] == e["turns"])),
    }
    if preload_docs is not None:
        out["preloadDocuments"] = len(preload_docs)
        out["preloadDocumentsWithoutEntity"] = sorted(d for d, e in preload_docs.items() if not e)
        if lexicon_entities is not None:
            known = set(lexicon_entities)
            out["preloadEntitiesNotInLexicon"] = sorted(e for e in static_counts if e not in known)
    if lexicon_entities is not None:
        out["lexiconEntities"] = len(lexicon_entities)
    if verbose:
        out["noneConfidenceMessages"] = none_messages
    return out


def tools_section(traces):
    """Shadow metric: per confidence, how many of the model's tool calls were inside the scope.

    `calledToolsInScope / calledTools` are the server's own counts over catalog names (a facade call
    is logged under its @Tool method name but the scope knows the facade class, and the server
    resolves that); `toolCallNamesInScope` is the raw name match against `scopeToolNames` and
    undercounts facades. Both are reported; the first is the one to read.
    """
    buckets = defaultdict(lambda: {"turns": 0, "calledTools": 0, "calledToolsInScope": 0,
                                   "toolCallNames": 0, "toolCallNamesInScope": 0, "ragFilterApplied": 0})
    for trace in traces:
        scope = trace.get("scope") or {}
        bucket = buckets[str(scope.get("confidence") or "UNKNOWN").upper()]
        bucket["turns"] += 1
        bucket["calledTools"] += scope.get("calledTools") or 0
        bucket["calledToolsInScope"] += scope.get("calledToolsInScope") or 0
        bucket["ragFilterApplied"] += 1 if scope.get("ragFilterApplied") else 0
        names = _tool_names(trace)
        in_scope = set(scope.get("scopeToolNames") or [])
        bucket["toolCallNames"] += len(names)
        bucket["toolCallNamesInScope"] += sum(1 for name in names if name in in_scope)
    out = {}
    for confidence, bucket in sorted(buckets.items()):
        bucket["calledInScopeShare"] = (
            bucket["calledToolsInScope"] / bucket["calledTools"] if bucket["calledTools"] else None)
        bucket["nameMatchShare"] = (
            bucket["toolCallNamesInScope"] / bucket["toolCallNames"] if bucket["toolCallNames"] else None)
        out[confidence] = bucket
    return out


def _pct(value):
    return "n/a" if value is None else f"{100 * value:.1f}%"


def _num(value):
    return "n/a" if value is None else f"{value:.4f}"


def _delta(value):
    return "n/a" if value is None else f"{value:+.4f}"


def _row(label, block):
    return (f"    {label:16}{_num(block['hitAtK']):>10}{_num(block['mrr']):>10}{_num(block['recallAtK']):>12}"
            f"{block['forbiddenHits']:>12}")


def _delta_row(label, deltas):
    return (f"    {label:16}{_delta(deltas['hitAtK']):>10}{_delta(deltas['mrr']):>10}"
            f"{_delta(deltas['recallAtK']):>12}{deltas['forbiddenHits']:>+12}")


def render_block(name, block, k):
    gate, simulated = block["gate"], block["simulatedGate"]
    status = f"enforce {gate['verdict']}" if gate else "no paired enforce sample"
    out = [f"{name}: samples={block['samples']} scored={block['scored']} paired={block['paired']}  [{status}; "
           f"simulated {simulated['verdict']}]"]
    out.append(f"    {'':16}{'hit@' + str(k):>10}{'MRR':>10}{'recall@' + str(k):>12}{'forbidden':>12}")
    out.append(_row("today (shadow)", block["today"]))
    if gate:
        if block["paired"] != block["samples"]:
            out.append(_row("today (paired)", block["todayPaired"]))
        out.append(_row("enforce (run)", block["enforce"]))
        out.append(_delta_row("delta enforce", gate["deltas"]))
    out.append(_row("simulated", block["simulated"]))
    out.append(_delta_row("delta simulated", simulated["deltas"]))
    out.append(f"    shadow docs retrieved={block['retrieved']} kept by the simulation={block['kept']}; expected doc "
               f"dropped by the simulation in {block['dropped']} sample(s)"
               + ("" if not gate else f", lost by the enforce run in {block['lost']}"))
    return out


def render_text(report):
    k = report["k"]
    out = [f"Scope-graph rag gate (ADR-0069 section 9), k={k}",
           f"Shadow traces: {report['traces']}; joined samples: {report['samples']}; fixtures: {report['fixtures']} "
           f"(joined {report['fixturesJoined']}, without a trace {report['fixturesWithoutTrace']})"]
    if report["enforceTraces"] is None:
        out.append("Enforce traces: none given (--enforce-file)")
    else:
        out.append(f"Enforce traces: {report['enforceTraces']}; fixtures joined {report['fixturesEnforceJoined']}, "
                   f"without a trace {report['fixturesWithoutEnforceTrace']}")
    if report["skipped"]:
        out.append("Skipped shadow traces: " + ", ".join(f"{why}={n}" for why, n in report["skipped"].items()))
    if report["enforceSkipped"]:
        out.append("Skipped enforce traces: " + ", ".join(f"{why}={n}" for why, n in report["enforceSkipped"].items()))
    if report["graphHashes"]:
        out.append("Graph snapshots: " + ", ".join(f"{h}={n}" for h, n in report["graphHashes"].items()))
    if report["enforcedConsumers"]:
        out.append("Enforced consumers (enforce run): "
                   + ", ".join(f"[{c}]={n}" for c, n in report["enforcedConsumers"].items()))
    if report["scopeTruncatedSamples"]:
        out.append(f"WARNING: {report['scopeTruncatedSamples']} sample(s) have a truncated scopeDocumentIds list; "
                   "their simulation may drop an in-scope document")
    if report["confidenceMismatches"]:
        out.append(f"WARNING: {report['confidenceMismatches']} pair(s) resolved a different scope confidence in the "
                   "enforce run (same graph, different tags?); they are bucketed by the shadow confidence")
    out.append("")
    out += render_block("overall", report["overall"], k)
    for name, block in report["bySet"].items():
        out.append("")
        out += render_block(f"set {name}", block, k)
    for name, block in report["byConfidence"].items():
        out.append("")
        out += render_block(f"confidence {name}", block, k)
    out.append("")
    gate = report["gate"]
    out.append(f"GATE: {gate['verdict']}" + (f" ({'; '.join(gate['reasons'])})" if gate["reasons"] else ""))
    if gate["overall"]:
        for failure in gate["overall"]["failures"]:
            out.append(f"    overall regression: {failure}")
    for name in gate["failingSets"]:
        for failure in report["bySet"][name]["gate"]["failures"]:
            out.append(f"    set {name} regression: {failure}")
    out.append(f"Simulated preview (not evidence: it cannot see the all-scope candidate pool): "
               f"{gate['simulated']['verdict']}")
    for failure in gate["simulated"]["failures"]:
        out.append(f"    simulated regression: {failure}")
    verbose_hint = "" if "regressionList" in report else " (--verbose lists them)"
    if report["enforceTraces"] is not None:
        out.append(f"Expected document lost by the enforce run in {report['losses']} sample(s){verbose_hint}")
    for entry in report.get("lossList", []):
        out.append(f"    lost {entry['fixtureId']} turn={entry['turnId']} enforceTurn={entry['enforceTurnId']} "
                   f"confidence={entry['confidence']} lost={entry['lost']} today={entry['today']} "
                   f"enforce={entry['enforce']}")
    out.append(f"Expected document dropped by the simulated filter in {report['regressions']} sample(s){verbose_hint}")
    for entry in report.get("regressionList", []):
        out.append(f"    {entry['fixtureId']} turn={entry['turnId']} confidence={entry['confidence']} "
                   f"dropped={entry['dropped']} retrieved={entry['retrieved']} scope={entry['scopeDocumentIds']}")
    for entry in report.get("forbiddenList", []):
        out.append(f"    forbidden {entry['fixtureId']} turn={entry['turnId']} today={entry['today']} "
                   f"simulated={entry['simulated']} enforce={entry['enforce']} new={entry['new']}")
    if report.get("fixturesWithoutTraceIds"):
        out.append("    no shadow trace for: " + ", ".join(str(f) for f in report["fixturesWithoutTraceIds"]))
    if report.get("fixturesWithoutEnforceTraceIds"):
        out.append("    no enforce trace for: " + ", ".join(str(f) for f in report["fixturesWithoutEnforceTraceIds"]))
    for entry in report.get("actorMismatches", []):
        out.append(f"    actor mismatch: turn={entry['turnId']} role={entry['role']} fixtures={entry['fixtureIds']}")
    out.append("")
    out.append("Tools (shadow metric only; the tools consumer is additive and needs its own gate)")
    out.append(f"    {'confidence':12}{'turns':>7}{'called':>8}{'in scope':>10}{'share':>8}"
               f"{'name match':>12}{'rag applied':>13}")
    for confidence, bucket in report["tools"].items():
        out.append(f"    {confidence:12}{bucket['turns']:>7}{bucket['calledTools']:>8}{bucket['calledToolsInScope']:>10}"
                   f"{_pct(bucket['calledInScopeShare']):>8}{_pct(bucket['nameMatchShare']):>12}"
                   f"{bucket['ragFilterApplied']:>13}")
    out.append("")
    out += render_coverage(report["coverage"])
    return "\n".join(out)


def render_coverage(coverage):
    out = [f"Documentation coverage: scoped turns={coverage['scopedTurns']} "
           f"NONE-confidence turns={coverage['noneConfidenceTurns']}"
           + (f" lexicon entities={coverage['lexiconEntities']}" if "lexiconEntities" in coverage else "")
           + (f" preload documents={coverage['preloadDocuments']}" if "preloadDocuments" in coverage else "")]
    has_static = "preloadDocuments" in coverage
    out.append(f"    {'entity':24}{'turns':>7}{'no-doc':>8}{'mean docs':>11}" + (f"{'static':>8}" if has_static else "")
               + "  match kinds")
    for name, entry in coverage["entities"].items():
        mean = "n/a" if entry["meanScopeDocuments"] is None else f"{entry['meanScopeDocuments']:.1f}"
        kinds = " ".join(f"{k}={v}" for k, v in entry["matchKinds"].items())
        static = f"{entry['staticDocuments']:>8}" if has_static else ""
        flag = "" if entry["inLexicon"] in (None, True) else "  (not in lexicon)"
        out.append(f"    {name:24}{entry['turns']:>7}{entry['turnsWithNoDocument']:>8}{mean:>11}{static}  {kinds}{flag}")
    out.append("    entities with no document: "
               + (", ".join(coverage["entitiesWithNoDocument"]) or "none"))
    if coverage.get("preloadDocumentsWithoutEntity"):
        out.append("    preload documents with no entity ([none]): " + ", ".join(coverage["preloadDocumentsWithoutEntity"]))
    if coverage.get("preloadEntitiesNotInLexicon"):
        out.append("    preload entities not in the lexicon: " + ", ".join(coverage["preloadEntitiesNotInLexicon"]))
    for message in coverage.get("noneConfidenceMessages", []):
        out.append(f"    NONE-confidence message: {message}")
    return out


def read_trace_files(paths):
    """Parse and merge trace exports; '-' reads stdin."""
    batches = []
    for path in paths:
        if path == "-":
            batches.append(parse_traces(sys.stdin.read()))
        else:
            with open(path, encoding="utf-8") as handle:
                batches.append(parse_traces(handle.read()))
    return merge_traces(batches)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Scope-graph rag promotion gate from shadow traces (ADR-0069 section 9).",
        epilog="PASS needs both runs: the simulated preview replays the section 6 rule over the shadow top-K and "
               "cannot see the all-scope candidate pool the enforce hook filters, so it decides nothing.",
    )
    parser.add_argument("--file", action="extend", nargs="+", metavar="PATH", required=True,
                        help="JSON/NDJSON export of the shadow run's eval turn traces ('-' for stdin); repeatable, "
                             "several paths allowed: merged, a turnId present twice is kept once")
    parser.add_argument("--enforce-file", action="extend", nargs="+", metavar="PATH",
                        help="JSON/NDJSON export of the same fixtures run with the rag consumer in enforce, against "
                             "the same graph snapshot; repeatable. Without it the verdict is at best NO_ENFORCE_RUN")
    parser.add_argument("--fixture", action="extend", nargs="+", metavar="PATH", required=True,
                        help="RAG fixture file (eval/rag-lexical/*.json, eval/rag-retrieval/*.json); repeatable")
    parser.add_argument("--k", type=int, default=DEFAULT_K,
                        help=f"rank cut for a fixture that names no k (default {DEFAULT_K})")
    parser.add_argument("--lexicon", metavar="PATH",
                        help="scope-graph/entities.yaml: its entity keys join the coverage section (with --preload)")
    parser.add_argument("--preload", metavar="PATH",
                        help="application.yml whose mcp.rag.preload.docs[].entities give the static document count "
                             "per entity")
    parser.add_argument("--verbose", action="store_true",
                        help="list the lost and dropped expected documents, forbidden hits, unmatched fixtures, "
                             "actor-mismatched turns and the NONE-confidence messages")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    args = parser.parse_args(argv)

    if (args.file + (args.enforce_file or [])).count("-") > 1:
        parser.error("stdin ('-') can be given once, to --file or --enforce-file")
    traces = read_trace_files(args.file)
    enforce_traces = None if args.enforce_file is None else read_trace_files(args.enforce_file)
    fixtures = []
    for path in args.fixture:
        with open(path, encoding="utf-8") as handle:
            fixtures.extend(load_fixture_file(path, handle.read()))

    lexicon_entities = preload_docs = None
    if args.lexicon:
        with open(args.lexicon, encoding="utf-8") as handle:
            lexicon_entities = read_lexicon_entities(handle.read())
    if args.preload:
        with open(args.preload, encoding="utf-8") as handle:
            preload_docs = read_preload_docs(handle.read())

    report = build_report(traces, fixtures, args.k, args.verbose, lexicon_entities, preload_docs, enforce_traces)
    print(json.dumps(report, indent=2) if args.json else render_text(report))
    return 0 if report["gate"]["verdict"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
