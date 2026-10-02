#!/usr/bin/env python3
"""Offline promotion gate for the scope graph's `rag` consumer (ADR-0069 section 9, spec 2.10/2.11).

Reads eval turn traces (EvalTurnTrace) recorded in `shadow`, joins each to a RAG fixture by its
`userMessage` and computes, from the trace's `scope.retrievedDocuments` (the final top-K handed to
the model, in rank order, one entry per document with its `rag_scope`):

  today            hit@k, MRR, recall@k and forbidden-document hits of the top-K as retrieved;
  simulated enforce the same four metrics over the documents the ADR section 6 filter rule would have
                   kept: when the trace's `scope.confidence` is HIGH, a document is kept when its
                   `documentId` is in `scope.scopeDocumentIds` or its `ragScope` is `master`;
                   on LOW / NONE every document is kept (today's eligibility, which the top-K already
                   passed). Order is preserved, nothing is added.

The gate (ADR-0069 section 9): PASS when, overall and within each fixture set, the simulated hit@k,
MRR and recall@k are each at least today's and the forbidden hits did not increase; otherwise FAIL
with the deltas. The report also lists the fixtures whose expected document the filter DROPPED (the
regression list; ids under --verbose), breaks the metrics down per confidence bucket and per fixture
set (rag-lexical vs rag-retrieval), and adds a shadow-only tools section (the share of the model's
tool calls that were inside the scope, per confidence). The `tools` consumer is additive (it never
removes a tool), so that section is a diagnostic: its promotion needs its own gate.

Documentation coverage (every run): per seed entity (`scope.seeds[].entity`), the turns it seeded, how
many of those resolved a scope with zero documents, and the mean scope document count. With
`--lexicon scope-graph/entities.yaml --preload application.yml` the section adds, per lexicon entity,
the static count of RAG documents annotated with it (`mcp.rag.preload.docs[].entities`), so the list of
entities with no document comes out of every run; `--verbose` also prints the messages of the
`NONE`-confidence turns (vocabulary the lexicon missed; the messages are test fixtures). The two yml
files are read by a minimal line reader (the entries are flat `- key:` / `- id:` / `entities: [..]`
lines), no yaml module needed.

Caveats the verdict carries with it:
  * The real hook filters the fused candidate pool BEFORE the top-K cut; the trace holds only the
    top-K. The simulation can therefore only drop, never promote a candidate that was cut at rank
    K+1, so it is pessimistic on recall: a PASS here is a PASS, a FAIL names the documents to look at.
  * A trace whose `scopeDocumentIdsTruncated` is true cannot be replayed exactly (an in-scope document
    beyond the cap looks out of scope); such samples are counted and flagged.
  * Traces written before the identity lists existed (`retrievedDocuments` null) carry counts only
    and are skipped.

Input:
  --file PATH      JSON array or NDJSON of EvalTurnTrace objects ('-' reads stdin). Repeatable; a
                   turn present in more than one export (same turnId) is kept once.
  --fixture PATH   RAG fixture file ({"fixtures":[{fixture_id, query, actor, expected:{doc_ids, k,
                   forbidden_doc_ids?}, rag_scope, tags}]}), from
                   pos-mcp-server/src/test/resources/eval/rag-lexical/ and eval/rag-retrieval/.
                   Repeatable. The fixture set is the file's parent directory name.

Join: a trace joins the fixture whose `query` equals its `userMessage` exactly, else after trim +
collapse whitespace + casefold (as scripts/tagging_shadow_report.py joins the tagging gate). A query
asked by several fixtures (a positive and a visibility-negative one differ by actor) is disambiguated
by the trace's `role` against the fixture's `actor.role`; fixtures left that expect the same documents
are one fixture (the first is used), anything else is counted as ambiguous and skipped.

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
    """Pair each scored trace with its fixture.

    Returns (matches, skipped) where matches is [(trace, fixture)] and skipped a Counter of why a
    trace joined nothing: noUserMessage, noFixture, ambiguousFixture.
    """
    exact, normalised = defaultdict(list), defaultdict(list)
    for fixture in fixtures:
        query = fixture.get("query")
        if isinstance(query, str):
            exact[query].append(fixture)
            normalised[normalise_text(query)].append(fixture)
    matches, skipped, ambiguous = [], Counter(), []
    for trace in traces:
        message = trace.get("userMessage")
        if not isinstance(message, str):
            skipped["noUserMessage"] += 1
            continue
        candidates = exact.get(message) or normalised.get(normalise_text(message)) or []
        if not candidates:
            skipped["noFixture"] += 1
            continue
        if len(candidates) > 1:
            role = trace.get("role")
            by_role = [f for f in candidates if (f.get("actor") or {}).get("role") == role]
            if by_role:
                candidates = by_role
            # Several fixtures that expect the same thing (duplicated across files) are one fixture.
            if len(candidates) > 1 and len({_expectation(f) for f in candidates}) > 1:
                skipped["ambiguousFixture"] += 1
                ambiguous.append({"turnId": trace.get("turnId"), "role": role,
                                  "fixtureIds": [f.get("fixture_id") for f in candidates]})
                continue
        matches.append((trace, candidates[0]))
    return matches, skipped, ambiguous


def _expectation(fixture):
    expected = fixture.get("expected") or {}
    return (tuple(sorted(expected.get("doc_ids") or [])),
            tuple(sorted(expected.get("forbidden_doc_ids") or fixture.get("forbidden_doc_ids") or [])),
            expected.get("k"))


def _mean(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None


def aggregate(samples):
    """Means of the rank metrics and the forbidden total, today and simulated, over a sample list."""
    out = {"samples": len(samples), "scored": sum(1 for s in samples if s["today"]["hitAtK"] is not None)}
    for side in ("today", "enforce"):
        block = {metric: _mean([s[side][metric] for s in samples]) for metric in METRICS}
        block["forbiddenHits"] = sum(len(s[side]["forbiddenHits"]) for s in samples)
        block["forbiddenSamples"] = sum(1 for s in samples if s[side]["forbiddenHits"])
        out[side] = block
    out["dropped"] = sum(1 for s in samples if s["dropped"])
    out["kept"] = sum(s["keptCount"] for s in samples)
    out["retrieved"] = sum(s["retrievedCount"] for s in samples)
    out["gate"] = verdict(out["today"], out["enforce"])
    return out


def verdict(today, enforce):
    """PASS when every rank metric held or improved and the forbidden hits did not grow; else FAIL + deltas."""
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
    return {"verdict": "FAIL" if failures else "PASS", "deltas": deltas, "failures": failures}


def _tool_names(trace):
    return [call.get("name") for call in trace.get("toolCalls") or [] if isinstance(call, dict)]


def build_report(traces, fixtures, k=DEFAULT_K, verbose=False, lexicon_entities=None, preload_docs=None):
    """Aggregate traces and fixtures into the report dict."""
    skipped = Counter()
    scoped = []
    for trace in traces:
        scope = trace.get("scope") if isinstance(trace, dict) else None
        if not isinstance(scope, dict):
            skipped["noScope"] += 1
            continue
        if scope.get("retrievedDocuments") is None:
            skipped["noRetrievedDocuments"] += 1
            continue
        scoped.append(trace)

    matches, join_skipped, ambiguous = join_traces(scoped, fixtures)
    skipped.update(join_skipped)

    samples = []
    for trace, fixture in matches:
        scope = trace["scope"]
        expected_block = fixture.get("expected") or {}
        expected = list(expected_block.get("doc_ids") or [])
        forbidden = list(expected_block.get("forbidden_doc_ids") or fixture.get("forbidden_doc_ids") or [])
        fixture_k = expected_block.get("k") or k
        retrieved = scope.get("retrievedDocuments") or []
        kept = simulate_enforce(scope)
        today = score(retrieved, expected, forbidden, fixture_k)
        enforce = score(kept, expected, forbidden, fixture_k)
        dropped_expected = sorted(
            set(expected) & {d.get("documentId") for d in retrieved} - {d.get("documentId") for d in kept}
        )
        samples.append({
            "turnId": trace.get("turnId"),
            "fixtureId": fixture.get("fixture_id"),
            "set": fixture["_set"],
            "confidence": str(scope.get("confidence") or "UNKNOWN").upper(),
            "mode": str(scope.get("mode") or "").upper(),
            "scopeTruncated": bool(scope.get("scopeDocumentIdsTruncated")),
            "retrievedCount": len(retrieved),
            "keptCount": len(kept),
            "today": today,
            "enforce": enforce,
            # The expected documents the filter removed from the top-K: the regression list.
            "dropped": dropped_expected,
            "retrievedIds": [d.get("documentId") for d in retrieved],
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

    failing_sets = [name for name, block in sets.items() if block["gate"]["verdict"] == "FAIL"]
    gate_verdict = "PASS" if overall["gate"]["verdict"] == "PASS" and not failing_sets else "FAIL"
    if not samples:
        gate_verdict = "NO_DATA"

    joined_fixture_ids = {s["fixtureId"] for s in samples}
    fixtures_without_trace = [f.get("fixture_id") for f in fixtures if f.get("fixture_id") not in joined_fixture_ids]

    report = {
        "k": k,
        "traces": len(traces),
        "skipped": dict(sorted(skipped.items())),
        "modes": dict(sorted(Counter(s["mode"] for s in samples).items())),
        "samples": len(samples),
        "fixtures": len(fixtures),
        "fixturesJoined": len(joined_fixture_ids),
        "fixturesWithoutTrace": len(fixtures_without_trace),
        "scopeTruncatedSamples": sum(1 for s in samples if s["scopeTruncated"]),
        "overall": overall,
        "bySet": sets,
        "byConfidence": confidences,
        "gate": {
            "verdict": gate_verdict,
            "overall": overall["gate"],
            "failingSets": failing_sets,
        },
        "tools": tools_section(scoped),
        "coverage": coverage_section(traces, lexicon_entities, preload_docs, verbose),
    }
    regressions = [s for s in samples if s["dropped"]]
    report["regressions"] = len(regressions)
    if verbose:
        report["regressionList"] = [
            {"fixtureId": s["fixtureId"], "turnId": s["turnId"], "confidence": s["confidence"],
             "dropped": s["dropped"], "retrieved": s["retrievedIds"], "scopeDocumentIds": s["scopeDocumentIds"]}
            for s in regressions
        ]
        report["forbiddenList"] = [
            {"fixtureId": s["fixtureId"], "turnId": s["turnId"], "today": s["today"]["forbiddenHits"],
             "enforce": s["enforce"]["forbiddenHits"]}
            for s in samples if s["today"]["forbiddenHits"] or s["enforce"]["forbiddenHits"]
        ]
        report["fixturesWithoutTraceIds"] = fixtures_without_trace
        report["ambiguousTraces"] = ambiguous
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


def render_block(name, block, k):
    today, enforce, gate = block["today"], block["enforce"], block["gate"]
    out = [f"{name}: samples={block['samples']} scored={block['scored']} docs retrieved={block['retrieved']} "
           f"kept={block['kept']} expected-doc dropped in {block['dropped']} sample(s)  [{gate['verdict']}]"]
    out.append(f"    {'':14}{'hit@' + str(k):>10}{'MRR':>10}{'recall@' + str(k):>12}{'forbidden':>12}")
    out.append(f"    {'today':14}{_num(today['hitAtK']):>10}{_num(today['mrr']):>10}{_num(today['recallAtK']):>12}"
               f"{today['forbiddenHits']:>12}")
    out.append(f"    {'enforce (sim)':14}{_num(enforce['hitAtK']):>10}{_num(enforce['mrr']):>10}"
               f"{_num(enforce['recallAtK']):>12}{enforce['forbiddenHits']:>12}")
    deltas = gate["deltas"]
    out.append(f"    {'delta':14}{_delta(deltas['hitAtK']):>10}{_delta(deltas['mrr']):>10}"
               f"{_delta(deltas['recallAtK']):>12}{deltas['forbiddenHits']:>+12}")
    return out


def render_text(report):
    k = report["k"]
    out = [f"Scope-graph rag gate (ADR-0069 section 9), k={k}",
           f"Traces: {report['traces']}; joined samples: {report['samples']}; fixtures: {report['fixtures']} "
           f"(joined {report['fixturesJoined']}, without a trace {report['fixturesWithoutTrace']})"]
    if report["skipped"]:
        out.append("Skipped traces: " + ", ".join(f"{why}={n}" for why, n in report["skipped"].items()))
    if report["modes"]:
        out.append("Trace modes: " + ", ".join(f"{m}={n}" for m, n in report["modes"].items()))
    if report["scopeTruncatedSamples"]:
        out.append(f"WARNING: {report['scopeTruncatedSamples']} sample(s) have a truncated scopeDocumentIds list; "
                   "their simulation may drop an in-scope document")
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
    out.append(f"GATE: {gate['verdict']}")
    for failure in gate["overall"]["failures"]:
        out.append(f"    overall regression: {failure}")
    for name in gate["failingSets"]:
        for failure in report["bySet"][name]["gate"]["failures"]:
            out.append(f"    set {name} regression: {failure}")
    out.append(f"Expected document dropped by the filter in {report['regressions']} sample(s)"
               + ("" if "regressionList" in report else " (--verbose lists them)"))
    for entry in report.get("regressionList", []):
        out.append(f"    {entry['fixtureId']} turn={entry['turnId']} confidence={entry['confidence']} "
                   f"dropped={entry['dropped']} retrieved={entry['retrieved']} scope={entry['scopeDocumentIds']}")
    for entry in report.get("forbiddenList", []):
        out.append(f"    forbidden {entry['fixtureId']} turn={entry['turnId']} today={entry['today']} "
                   f"enforce={entry['enforce']}")
    if report.get("fixturesWithoutTraceIds"):
        out.append("    no trace for: " + ", ".join(str(f) for f in report["fixturesWithoutTraceIds"]))
    for entry in report.get("ambiguousTraces", []):
        out.append(f"    ambiguous: turn={entry['turnId']} role={entry['role']} fixtures={entry['fixtureIds']}")
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


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Scope-graph rag promotion gate from shadow traces (ADR-0069 section 9).",
        epilog="The simulation replays the section 6 filter rule over the recorded top-K only, so it cannot "
               "promote a candidate the cut removed: it is pessimistic on recall.",
    )
    parser.add_argument("--file", action="extend", nargs="+", metavar="PATH", required=True,
                        help="JSON/NDJSON export of eval turn traces ('-' for stdin); repeatable, several paths "
                             "allowed: merged, a turnId present twice is kept once")
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
                        help="list the dropped expected documents, forbidden hits, unmatched fixtures, ambiguous "
                             "traces and the NONE-confidence messages")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    args = parser.parse_args(argv)

    if args.file.count("-") > 1:
        parser.error("stdin ('-') can be given to --file once")
    batches = []
    for path in args.file:
        if path == "-":
            batches.append(parse_traces(sys.stdin.read()))
        else:
            with open(path, encoding="utf-8") as handle:
                batches.append(parse_traces(handle.read()))
    traces = merge_traces(batches)
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

    report = build_report(traces, fixtures, args.k, args.verbose, lexicon_entities, preload_docs)
    print(json.dumps(report, indent=2) if args.json else render_text(report))
    return 0 if report["gate"]["verdict"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
