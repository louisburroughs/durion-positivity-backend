#!/usr/bin/env python3
"""Shadow report for pre-LLM question tagging (ADR-0068 section 6, spec 2.9).

Reads eval turn traces that carry a `tagging` block (TagTrace) and prints, per provider model,
turn count, tagging latency p50/p95, fallback rate by reason and state-truncation rate; and per
tag the model/heuristic agreement rate, a model-confidence histogram (deciles) and the agreement
rate at each candidate threshold (0.50-0.95 step 0.05), so an operator can pick
`mcp.tagging.thresholds.<tag>`.

Input, one of:
  --file PATH        JSON array or NDJSON of EvalTurnTrace objects ("-" reads stdin)
  --base-url URL     page GET {URL}/v1/eval/turn-traces?since=...&limit=... with --token
                     (the endpoint returns the CALLER'S OWN traces only, newest first, at most 200
                     per call, and has no cursor: use --window-hours to keep the window under
                     200 turns, or export the traces and use --file).

Language: EvalTurnTrace carries no language field, so a language split is one report per gate run
(en, fr, es). --messages-file maps turnId -> language ({"<turnId>": "fr"} JSON) for a mixed
export, and --language then filters on it.

Stdlib only, like scripts/nlti_live_verify.py.
"""

import argparse
import json
import math
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


def fetch_traces(base_url, token, since, limit, timeout=30):
    query = urllib.parse.urlencode({"since": since, "limit": limit})
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/v1/eval/turn-traces?{query}",
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def _num(value):
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def build_report(traces, language=None, languages=None):
    """Aggregate traces into the report dict. Traces without a tagging block are counted and skipped."""
    models = defaultdict(lambda: {"turns": 0, "latencies": [], "fallbacks": Counter(), "truncated": 0})
    tags = defaultdict(lambda: {"compared": 0, "agree": 0, "confidences": []})
    skipped = 0
    for trace in traces:
        if language and (languages or {}).get(str(trace.get("turnId"))) != language:
            continue
        tagging = trace.get("tagging")
        if not isinstance(tagging, dict):
            skipped += 1
            continue
        model = models[tagging.get("providerModel") or "unknown"]
        model["turns"] += 1
        latency = _num(tagging.get("latencyMs"))
        if latency is not None:
            model["latencies"].append(latency)
        if tagging.get("fallbackReason"):
            model["fallbacks"][tagging["fallbackReason"]] += 1
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

    report = {"turnsWithoutTagging": skipped, "models": {}, "tags": {}}
    for name, m in sorted(models.items()):
        turns = m["turns"]
        report["models"][name] = {
            "turns": turns,
            "latencyP50Ms": percentile(m["latencies"], 0.50),
            "latencyP95Ms": percentile(m["latencies"], 0.95),
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
    return report


def _pct(value):
    return "-" if value is None else f"{value * 100:5.1f}%"


def _ms(value):
    return "-" if value is None else f"{value:.0f}"


def render_text(report):
    out = [f"Turns without a tagging block (skipped): {report['turnsWithoutTagging']}", ""]
    out.append(f"{'provider model':<24}{'turns':>7}{'p50ms':>8}{'p95ms':>8}{'fallback':>10}{'truncated':>11}")
    for name, m in report["models"].items():
        out.append(
            f"{name:<24}{m['turns']:>7}{_ms(m['latencyP50Ms']):>8}{_ms(m['latencyP95Ms']):>8}"
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
    return "\n".join(out)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Question-tagging shadow report (ADR-0068 section 6).",
        epilog="Agreement at a threshold is computed over the answers whose model confidence is at or above it.",
    )
    parser.add_argument("--file", help="JSON/NDJSON export of eval turn traces ('-' for stdin)")
    parser.add_argument("--base-url", help="gateway or pos-mcp-server base URL for GET /v1/eval/turn-traces")
    parser.add_argument("--token", help="bearer token of the actor whose traces to read (with --base-url)")
    parser.add_argument("--window-hours", type=float, default=2.0, help="look-back window for --base-url (default 2)")
    parser.add_argument("--limit", type=int, default=PAGE_LIMIT, help=f"max traces to fetch (server cap {PAGE_LIMIT})")
    parser.add_argument("--language", help="keep only turns mapped to this language by --messages-file")
    parser.add_argument("--messages-file", help="JSON {turnId: language} for --language")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    args = parser.parse_args(argv)

    if bool(args.file) == bool(args.base_url):
        parser.error("give exactly one of --file or --base-url")
    if args.language and not args.messages_file:
        parser.error("--language needs --messages-file (traces carry no language field)")

    if args.file:
        text = sys.stdin.read() if args.file == "-" else open(args.file, encoding="utf-8").read()
        traces = parse_traces(text)
    else:
        if not args.token:
            parser.error("--base-url needs --token")
        since = (datetime.now(timezone.utc) - timedelta(hours=args.window_hours)).strftime("%Y-%m-%dT%H:%M:%SZ")
        try:
            traces = fetch_traces(args.base_url, args.token, since, min(args.limit, PAGE_LIMIT))
        except (urllib.error.URLError, OSError) as exc:
            print(f"fetch failed: {exc}", file=sys.stderr)
            return 2
        if len(traces) >= PAGE_LIMIT:
            print(f"warning: {PAGE_LIMIT} traces returned, the window may be truncated; shrink --window-hours",
                  file=sys.stderr)

    languages = None
    if args.messages_file:
        with open(args.messages_file, encoding="utf-8") as handle:
            languages = json.load(handle)
    report = build_report(traces, args.language, languages)
    print(json.dumps(report, indent=2) if args.json else render_text(report))
    return 0


if __name__ == "__main__":
    sys.exit(main())
