#!/usr/bin/env python3
"""Shadow report for pre-LLM question tagging (ADR-0068 section 6, spec 2.9).

Reads eval turn traces (EvalTurnTrace) whose `tags` block (TagTrace: mode, providerModel,
latencyMs, fallbackReason, stateTruncated, tags[]) is present and prints, per provider model, turn
count, tagging latency p50/p95 over every provider call (fallbacks included: a timeout pays the whole
budget, so a model that often times out cannot look fast), the same percentiles over the turns the model
answered and over the fallback turns as diagnostics, fallback rate by reason and state-truncation rate; and per tag the
model/heuristic agreement rate, a model-confidence histogram (deciles) and the agreement rate at
each candidate threshold (0.50-0.95 step 0.05), so an operator can pick
`mcp.tagging.thresholds.<tag>`. Turns recorded in mode OFF carry the heuristic answers alone (no
provider was called) and are counted, then skipped.

Input, one of:
  --file PATH        JSON array or NDJSON of EvalTurnTrace objects ("-" reads stdin). Repeatable,
                     and several paths may follow one --file: the files are merged and a turn
                     present in more than one (same turnId) is kept once, so overlapping batch
                     exports are safe.
  --base-url URL     GET {URL}/v1/eval/turn-traces?since=...&limit=... with --token, sending
                     X-API-Version ($MCP_API_VERSION, default 1). The endpoint returns the
                     CALLER'S OWN traces only, newest first, at most 200 per call, and has no
                     cursor: a full gate set (328-355 utterances per language) does not fit one
                     call. Run it in batches of fewer than 200 turns, export each batch, and pass
                     the exports to --file (README of the tagging-gate fixtures, "Bake-off
                     procedure"). A warning is printed when a call returns as many traces as the
                     limit, since older turns were then cut off.

Language: EvalTurnTrace carries no language field, so a language split is one report per gate run
(en, fr, es). --messages-file maps turnId -> language ({"<turnId>": "fr"} JSON) for a mixed
export, and --language then filters on it.

Ground truth (spec 2.9): --expected FIXTURE (repeatable, one per language) joins each trace to a gate
utterance of pos-mcp-server/src/test/resources/eval/tagging-gate/*.json by its `userMessage` (exact
text, else trim + collapse whitespace + casefold) and scores, per language and per tag, the
heuristic's accuracy and the accuracy the merge rule would reach with the model at each candidate
threshold (the model's `modelValue` when its confidence is at or above the threshold, the heuristic's
value below it). Asymmetric tags also get the false-non-IDLE rate (workflow_state) and the
model-true/heuristic-false rate with its precision (simple_chat, admin_account_question); `entity` is
scored as a set (precision/recall over the entity_<key> answers). --verbose lists what did not join.

Stdlib only, like scripts/nlti_live_verify.py.
"""

import argparse
import json
import math
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone

THRESHOLDS = [round(0.50 + 0.05 * i, 2) for i in range(10)]  # 0.50 .. 0.95
PAGE_LIMIT = 200  # EvalTurnTraceQueryService.MAX_RESULTS


def percentile(values, q):
    """Nearest-rank percentile; None for an empty list."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(q * len(ordered)))
    return ordered[rank - 1]


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


def build_request(base_url, token, since, limit):
    """The GET request for one page of the caller's traces (gateway-compatible headers)."""
    query = urllib.parse.urlencode({"since": since, "limit": limit})
    return urllib.request.Request(
        f"{base_url.rstrip('/')}/v1/eval/turn-traces?{query}",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
            # The gateway routes on X-API-Version, like scripts/analytics_gate_run.py.
            "X-API-Version": os.environ.get("MCP_API_VERSION", "1"),
        },
    )


def fetch_traces(base_url, token, since, limit, timeout=30):
    with urllib.request.urlopen(build_request(base_url, token, since, limit), timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def _num(value):
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def _is_off(tagging):
    return str(tagging.get("mode") or "").upper() == "OFF"


def build_report(traces, language=None, languages=None, expected=None, verbose=False):
    """Aggregate traces into the report dict.

    Traces without a `tags` block are counted (`turnsWithoutTagging`) and skipped; traces whose
    block has mode OFF carry the heuristic answers alone, no provider call, so they are counted
    (`turnsModeOff`) and skipped too, ground truth included. Latency percentiles cover the turns
    with no fallbackReason (the model answered); fallback turns get their own percentiles.

    `expected` is a list of gate fixtures (dicts with `language` and `utterances`); when given, the
    report gains a `groundTruth` section (see score_against_fixture).
    """
    tagged = []
    models = defaultdict(lambda: {"turns": 0, "latencies": [], "answeredLatencies": [], "fallbackLatencies": [],
                                  "fallbacks": Counter(),
                                  "truncated": 0})
    tags = defaultdict(lambda: {"compared": 0, "agree": 0, "confidences": []})
    skipped = 0
    off = 0
    for trace in traces:
        if language and (languages or {}).get(str(trace.get("turnId"))) != language:
            continue
        tagging = trace.get("tags")
        if not isinstance(tagging, dict):
            skipped += 1
            continue
        if _is_off(tagging):
            off += 1
            continue
        tagged.append(trace)
        model = models[tagging.get("providerModel") or "unknown"]
        model["turns"] += 1
        latency = _num(tagging.get("latencyMs"))
        fallback = tagging.get("fallbackReason")
        if latency is not None:
            model["latencies"].append(latency)
            model["fallbackLatencies" if fallback else "answeredLatencies"].append(latency)
        if fallback:
            model["fallbacks"][fallback] += 1
        if tagging.get("stateTruncated"):
            model["truncated"] += 1
        for tag in tagging.get("tags") or []:
            stat = tags[tag.get("name") or "unknown"]
            confidence = _num(tag.get("modelConfidence"))
            agree = tag.get("agree")
            if agree is None or confidence is None:
                continue  # the model or the heuristic did not answer: nothing to compare
            stat["compared"] += 1
            stat["agree"] += 1 if agree else 0
            stat["confidences"].append((confidence, bool(agree)))

    report = {"turnsWithoutTagging": skipped, "turnsModeOff": off, "models": {}, "tags": {}}
    for name, m in sorted(models.items()):
        turns = m["turns"]
        report["models"][name] = {
            "turns": turns,
            "answeredTurns": turns - sum(m["fallbacks"].values()),
            "latencyP50Ms": percentile(m["latencies"], 0.50),
            "latencyP95Ms": percentile(m["latencies"], 0.95),
            "answeredLatencyP50Ms": percentile(m["answeredLatencies"], 0.50),
            "answeredLatencyP95Ms": percentile(m["answeredLatencies"], 0.95),
            "fallbackLatencyP50Ms": percentile(m["fallbackLatencies"], 0.50),
            "fallbackLatencyP95Ms": percentile(m["fallbackLatencies"], 0.95),
            "fallbackRate": sum(m["fallbacks"].values()) / turns if turns else 0.0,
            "fallbackByReason": {r: c / turns for r, c in sorted(m["fallbacks"].items())},
            "stateTruncationRate": m["truncated"] / turns if turns else 0.0,
        }
    for name, t in sorted(tags.items()):
        pairs = t["confidences"]
        histogram = [0] * 10
        for confidence, _ in pairs:
            histogram[min(9, max(0, int(confidence * 10)))] += 1
        at = {}
        for threshold in THRESHOLDS:
            kept = [a for c, a in pairs if c >= threshold]
            at[f"{threshold:.2f}"] = {
                "answered": len(kept),
                "agreementRate": (sum(kept) / len(kept)) if kept else None,
            }
        report["tags"][name] = {
            "compared": t["compared"],
            "agreementRate": t["agree"] / t["compared"] if t["compared"] else None,
            "confidenceDeciles": histogram,
            "atThreshold": at,
        }
    if expected:
        report["groundTruth"] = score_against_fixtures(tagged, expected, verbose)
    return report


# ---------------------------------------------------------------------------------------------
# Ground truth scoring (spec 2.9)

ASYMMETRIC_PROMOTION_TAGS = ("simple_chat", "admin_account_question")
ENTITY_PREFIXES = ("entity_", "entity.")


def normalise_text(text):
    """Trim, collapse whitespace, casefold: the fallback join key."""
    return " ".join(str(text).split()).casefold()


def _value(raw):
    """A tag value as a comparable string: booleans 'true'/'false', everything else casefolded."""
    if raw is None:
        return None
    if isinstance(raw, bool):
        return "true" if raw else "false"
    return str(raw).strip().casefold()


def _entity_key(name):
    for prefix in ENTITY_PREFIXES:
        if name.startswith(prefix):
            return name[len(prefix):]
    return None


def join_traces(traces, fixture):
    """Pair each trace with the fixture utterance it asked: exact userMessage, else normalised.

    Returns (matches, unmatched_traces, unmatched_utterances) where matches is [(trace, utterance)].
    """
    exact, normalised = {}, {}
    for utterance in fixture.get("utterances", []):
        exact.setdefault(utterance["text"], utterance)
        normalised.setdefault(normalise_text(utterance["text"]), utterance)
    matches, unmatched = [], []
    seen = set()
    for trace in traces:
        message = trace.get("userMessage")
        utterance = None
        if isinstance(message, str):
            utterance = exact.get(message) or normalised.get(normalise_text(message))
        if utterance is None:
            unmatched.append(trace)
        else:
            matches.append((trace, utterance))
            seen.add(utterance["id"])
    missing = [u for u in fixture.get("utterances", []) if u["id"] not in seen]
    return matches, unmatched, missing


def _acting(heuristic, model, confidence, threshold):
    """The merge rule: the model's answer at or above the threshold, the heuristic's below it."""
    if model is not None and confidence is not None and confidence >= threshold:
        return model
    return heuristic


def score_fixture_matches(matches):
    """Score joined (trace, utterance) pairs: per tag, then the entity set. See the module docstring."""
    tag_stats = {}
    entity = {f"{t:.2f}": {"predicted": 0, "expected": 0, "hit": 0} for t in THRESHOLDS}
    for trace, utterance in matches:
        entries = {}
        for entry in (trace.get("tags") or {}).get("tags") or []:
            entries[entry.get("name") or "unknown"] = entry
        expected_tags = utterance.get("expected_tags") or {}
        for tag, raw_expected in expected_tags.items():
            if tag == "entity":
                continue
            entry = entries.get(tag)
            stat = tag_stats.setdefault(tag, {
                "samples": 0, "missing": 0, "heuristicN": 0, "heuristicCorrect": 0,
                "idleN": 0, "heuristicNonIdle": 0,
                "atThreshold": {f"{t:.2f}": {"n": 0, "correct": 0, "nonIdle": 0, "flip": 0, "flipTrue": 0}
                                for t in THRESHOLDS},
            })
            stat["samples"] += 1
            if entry is None:
                stat["missing"] += 1
                continue
            want = _value(raw_expected)
            heuristic = _value(entry.get("heuristicValue"))
            model = _value(entry.get("modelValue"))
            confidence = _num(entry.get("modelConfidence"))
            if heuristic is not None:
                stat["heuristicN"] += 1
                stat["heuristicCorrect"] += 1 if heuristic == want else 0
                if tag == "workflow_state" and want == "idle":
                    stat["idleN"] += 1
                    stat["heuristicNonIdle"] += 1 if heuristic != "idle" else 0
            elif tag == "workflow_state" and want == "idle":
                stat["idleN"] += 1
            for threshold in THRESHOLDS:
                at = stat["atThreshold"][f"{threshold:.2f}"]
                acting = _acting(heuristic, model, confidence, threshold)
                if acting is None:
                    continue
                at["n"] += 1
                at["correct"] += 1 if acting == want else 0
                if tag == "workflow_state" and want == "idle" and acting != "idle":
                    at["nonIdle"] += 1
                if tag in ASYMMETRIC_PROMOTION_TAGS and heuristic == "false" and acting == "true":
                    at["flip"] += 1
                    at["flipTrue"] += 1 if want == "true" else 0
        want_entities = set(expected_tags.get("entity") or [])
        for threshold in THRESHOLDS:
            cell = entity[f"{threshold:.2f}"]
            predicted = set()
            for name, entry in entries.items():
                key = _entity_key(name)
                confidence = _num(entry.get("modelConfidence"))
                if (key is not None and _value(entry.get("modelValue")) == "true"
                        and confidence is not None and confidence >= threshold):
                    predicted.add(key)
            cell["predicted"] += len(predicted)
            cell["expected"] += len(want_entities)
            cell["hit"] += len(predicted & want_entities)

    def ratio(a, b):
        return a / b if b else None

    tags = {}
    for tag, stat in sorted(tag_stats.items()):
        at = {}
        for label, cell in stat["atThreshold"].items():
            row = {"n": cell["n"], "modelAccuracy": ratio(cell["correct"], cell["n"])}
            if tag == "workflow_state":
                row["falseNonIdleRate"] = ratio(cell["nonIdle"], stat["idleN"])
            if tag in ASYMMETRIC_PROMOTION_TAGS:
                row["modelTrueHeuristicFalseRate"] = ratio(cell["flip"], cell["n"])
                row["modelTrueHeuristicFalsePrecision"] = ratio(cell["flipTrue"], cell["flip"])
            at[label] = row
        entry = {
            "samples": stat["samples"],
            "missing": stat["missing"],
            "heuristicN": stat["heuristicN"],
            "heuristicAccuracy": ratio(stat["heuristicCorrect"], stat["heuristicN"]),
            "atThreshold": at,
        }
        if tag == "workflow_state":
            entry["heuristicFalseNonIdleRate"] = ratio(stat["heuristicNonIdle"], stat["idleN"])
            entry["idleSamples"] = stat["idleN"]
        tags[tag] = entry
    entity_rows = {}
    for label, cell in entity.items():
        entity_rows[label] = {
            "predicted": cell["predicted"],
            "expected": cell["expected"],
            "precision": ratio(cell["hit"], cell["predicted"]),
            "recall": ratio(cell["hit"], cell["expected"]),
        }
    return {"samples": len(matches), "tags": tags, "entity": entity_rows}


def score_against_fixtures(traces, fixtures, verbose=False):
    """Join the traces to every fixture and score each language. A trace joins every fixture it matches."""
    languages = {}
    joined_anywhere = set()
    for fixture in fixtures:
        name = fixture.get("language") or "unknown"
        matches, unmatched, missing = join_traces(traces, fixture)
        for trace, _ in matches:
            joined_anywhere.add(id(trace))
        result = score_fixture_matches(matches)
        result.update({
            "matchedTraces": len(matches),
            "matchedUtterances": len(fixture.get("utterances", [])) - len(missing),
            "unmatchedUtterances": len(missing),
            "reviewed": fixture.get("reviewed"),
        })
        if verbose:
            result["unmatchedUtteranceIds"] = [u["id"] for u in missing]
        languages[name] = result
    unmatched_traces = [t for t in traces if id(t) not in joined_anywhere]
    out = {"languages": languages, "unmatchedTraces": len(unmatched_traces)}
    if verbose:
        out["unmatchedTraceMessages"] = [str(t.get("userMessage")) for t in unmatched_traces]
    return out


def _pct(value):
    return "-" if value is None else f"{value * 100:5.1f}%"


def _ms(value):
    return "-" if value is None else f"{value:.0f}"


def render_text(report):
    out = [f"Turns without a tags block (skipped): {report['turnsWithoutTagging']}",
           f"Turns in mode OFF, heuristic only (skipped): {report['turnsModeOff']}", ""]
    out.append("p50/p95: every provider call (the ADR-0068 section 6 latency); ok p95: answered turns; "
               "fb p50/p95: turns that fell back to the heuristic")
    out.append(f"{'provider model':<24}{'turns':>7}{'p50ms':>8}{'p95ms':>8}{'ok p95':>8}{'fb p50':>8}{'fb p95':>8}"
               f"{'fallback':>10}{'truncated':>11}")
    for name, m in report["models"].items():
        out.append(
            f"{name:<24}{m['turns']:>7}{_ms(m['latencyP50Ms']):>8}{_ms(m['latencyP95Ms']):>8}"
            f"{_ms(m['answeredLatencyP95Ms']):>8}"
            f"{_ms(m['fallbackLatencyP50Ms']):>8}{_ms(m['fallbackLatencyP95Ms']):>8}"
            f"{_pct(m['fallbackRate']):>10}{_pct(m['stateTruncationRate']):>11}"
        )
        for reason, rate in m["fallbackByReason"].items():
            out.append(f"    fallback {reason:<20}{_pct(rate)}")
    out += ["", "Per tag: agreement with the heuristic, model-confidence deciles (0.0-0.1 ... 0.9-1.0)"]
    for name, t in report["tags"].items():
        out.append(f"{name:<22} compared={t['compared']:<5} agreement={_pct(t['agreementRate'])}")
        out.append("    deciles  " + " ".join(f"{n:>4}" for n in t["confidenceDeciles"]))
        out.append("    thresh   " + " ".join(f"{k:>6}" for k in t["atThreshold"]))
        out.append("    agree    " + " ".join(f"{_pct(v['agreementRate']):>6}" for v in t["atThreshold"].values()))
        out.append("    answered " + " ".join(f"{v['answered']:>6}" for v in t["atThreshold"].values()))
    if "groundTruth" in report:
        out += ["", render_ground_truth(report["groundTruth"])]
    return "\n".join(out)


def render_ground_truth(truth):
    out = [f"Ground truth: {truth['unmatchedTraces']} trace(s) joined no gate utterance"]
    if truth.get("unmatchedTraceMessages"):
        out += [f"    unmatched trace: {m}" for m in truth["unmatchedTraceMessages"]]
    for language, lang in truth["languages"].items():
        out.append("")
        reviewed = "" if lang.get("reviewed") else "  (fixture NOT reviewed by a native reader)"
        out.append(f"[{language}] traces joined={lang['matchedTraces']} utterances joined={lang['matchedUtterances']}"
                   f" utterances without a trace={lang['unmatchedUtterances']}{reviewed}")
        if lang.get("unmatchedUtteranceIds"):
            out.append("    no trace for: " + ", ".join(lang["unmatchedUtteranceIds"]))
        for tag, t in lang["tags"].items():
            out.append(f"{tag:<24} samples={t['samples']:<4} heuristic={_pct(t['heuristicAccuracy'])}")
            out.append("    thresh   " + " ".join(f"{k:>6}" for k in t["atThreshold"]))
            out.append("    model    " + " ".join(f"{_pct(v['modelAccuracy']):>6}" for v in t["atThreshold"].values()))
            if tag == "workflow_state":
                out.append(f"    false-non-IDLE: heuristic {_pct(t['heuristicFalseNonIdleRate'])}; model+fallback "
                           + " ".join(f"{_pct(v['falseNonIdleRate']):>6}" for v in t["atThreshold"].values()))
            if tag in ASYMMETRIC_PROMOTION_TAGS:
                out.append("    model-true/heuristic-false rate "
                           + " ".join(f"{_pct(v['modelTrueHeuristicFalseRate']):>6}" for v in t["atThreshold"].values()))
                out.append("    ... precision                   "
                           + " ".join(f"{_pct(v['modelTrueHeuristicFalsePrecision']):>6}"
                                      for v in t["atThreshold"].values()))
        out.append("entity (set)")
        out.append("    thresh   " + " ".join(f"{k:>6}" for k in lang["entity"]))
        out.append("    precision " + " ".join(f"{_pct(v['precision']):>6}" for v in lang["entity"].values()))
        out.append("    recall    " + " ".join(f"{_pct(v['recall']):>6}" for v in lang["entity"].values()))
    return "\n".join(out)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Question-tagging shadow report (ADR-0068 section 6).",
        epilog="Agreement at a threshold is computed over the answers whose model confidence is at or above it.",
    )
    parser.add_argument("--file", action="extend", nargs="+", metavar="PATH",
                        help="JSON/NDJSON export of eval turn traces ('-' for stdin); repeatable, several paths "
                             "allowed: merged, a turnId present twice is kept once")
    parser.add_argument("--base-url", help="gateway or pos-mcp-server base URL for GET /v1/eval/turn-traces")
    parser.add_argument("--token", help="bearer token of the actor whose traces to read (with --base-url)")
    parser.add_argument("--window-hours", type=float, default=2.0, help="look-back window for --base-url (default 2)")
    parser.add_argument("--limit", type=int, default=PAGE_LIMIT, help=f"max traces to fetch (server cap {PAGE_LIMIT})")
    parser.add_argument("--language", help="keep only turns mapped to this language by --messages-file")
    parser.add_argument("--messages-file", help="JSON {turnId: language} for --language")
    parser.add_argument("--expected", action="append", metavar="FIXTURE",
                        help="tagging-gate fixture (en.json, fr-CA.json, es.json; repeatable): score both taggers "
                             "against its expected_tags, per language")
    parser.add_argument("--verbose", action="store_true", help="with --expected, list the unmatched traces/utterances")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    args = parser.parse_args(argv)

    if bool(args.file) == bool(args.base_url):
        parser.error("give exactly one of --file or --base-url")
    if args.language and not args.messages_file:
        parser.error("--language needs --messages-file (traces carry no language field)")

    if args.file:
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
    else:
        if not args.token:
            parser.error("--base-url needs --token")
        since = (datetime.now(timezone.utc) - timedelta(hours=args.window_hours)).strftime("%Y-%m-%dT%H:%M:%SZ")
        limit = max(1, min(args.limit, PAGE_LIMIT))
        try:
            traces = fetch_traces(args.base_url, args.token, since, limit)
        except (urllib.error.URLError, OSError) as exc:
            print(f"fetch failed: {exc}", file=sys.stderr)
            return 2
        if len(traces) >= limit:
            print(f"warning: {len(traces)} traces returned, the limit ({limit}); older turns in the window were "
                  "cut off. Shrink --window-hours, or run the gate in batches of fewer than "
                  f"{PAGE_LIMIT} turns and pass each batch's export to --file", file=sys.stderr)

    languages = None
    if args.messages_file:
        with open(args.messages_file, encoding="utf-8") as handle:
            languages = json.load(handle)
    expected = None
    if args.expected:
        expected = []
        for path in args.expected:
            with open(path, encoding="utf-8") as handle:
                expected.append(json.load(handle))
    report = build_report(traces, args.language, languages, expected, args.verbose)
    print(json.dumps(report, indent=2) if args.json else render_text(report))
    return 0


if __name__ == "__main__":
    sys.exit(main())
