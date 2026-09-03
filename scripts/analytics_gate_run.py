#!/usr/bin/env python3
"""Chat-path analytics gate runner (#1671).

Sends the versioned analytics-gate questions to a running stack's chat endpoint and writes a run
record that is reproducible: the questions come from
`pos-mcp-server/src/test/resources/eval/analytics-gate/QUESTIONS.json` and the run record carries
that file's git blob sha, so a later reader can recover the exact text that produced a score.

Before this script the twelve utterances lived in an ad-hoc list on the operator's machine
(`/tmp/gate_questions.json`). Nothing in git recorded what was asked, so a re-run could silently
ask something different and move the score with no code change, and a regression could not be told
apart from a reworded question. The 2026-09-03 q09 UNSCORABLE verdict was exactly that failure:
the question asked live carried a calendar-year window, the versioned fixture carried none, and
EXPECTED.md Q9 measures twelve calendar months.

The scoring itself is deliberately NOT automated here. Plan §2.1 criterion 1 is "the ground-truth
script is the specification of the expected answer", and comparing a prose answer to it is a human
judgement (see docs/gate-runs/wave-2/*). This script collects evidence and pins provenance; a
grader fills in the verdicts.

Tool-call observability (#1676): a composition question (q05's aged-receivables-then-per-customer
plan being the motivating case) can fail by silently truncating the plan — the model runs the first
call, then offers a menu of partial answers instead of the remaining per-customer calls. That
failure mode should be visible on the run document rather than reading as an ordinary wrong answer.
`POST /mcp/chat` (`McpChatController.ChatResponse`) carries only the final response text, no tool
name or count, and no admin/observability endpoint exposes `mcp_tool_invocation_log` over HTTP —
grep confirmed no controller reads `ToolAuditRepository`/`ToolAuditService`. This script does not
build one (out of scope here); it records that gap honestly instead. Every result therefore carries
a `tool_calls` field that is always `null` today, a "Tool calls" column reading `n/a (not exposed
by the endpoint)`, and — for a question whose QUESTIONS.json entry carries `expected_plan` — a
`plan_check` field explaining why no automatic verdict was possible. The comparison logic
(`check_expected_plan`) is written to activate the moment `tool_calls` becomes observable (a future
admin endpoint, or a `--admin-url` this script would then grow), so wiring in real data later is a
one-line change here, not a rewrite.

Usage:
    scripts/alpha-itest-tunnel.sh                      # SSM port-forward, if running against alpha
    python3 scripts/analytics_gate_run.py --out /tmp/gaterun3
    python3 scripts/analytics_gate_run.py --out /tmp/q09 --only q09,q12
    python3 scripts/analytics_gate_run.py --out /tmp/all --all      # includes excluded questions

Config (env, or a --env-file in KEY=VALUE form — the itest credentials file is the usual source):
    MCP_CHAT_URL        default http://localhost:18086/mcp-server/v1/mcp/chat
    MCP_BEARER_TOKEN    bearer token for the calling actor (required unless --token is given)
    MCP_API_VERSION     default 1, sent as X-API-Version when going through the gateway

Credentials are read from the environment or the env file and are never printed or written into the
run record. Stdlib only.
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
QUESTIONS_PATH = ROOT / "pos-mcp-server/src/test/resources/eval/analytics-gate/QUESTIONS.json"
DEFAULT_URL = "http://localhost:18086/mcp-server/v1/mcp/chat"


def load_env_file(path):
    """Read KEY=VALUE lines into the environment without overwriting what is already set."""
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def git(*args):
    """Run a git command in the repo, returning stripped stdout or None if git is unavailable."""
    try:
        out = subprocess.run(
            ["git", *args], cwd=ROOT, capture_output=True, text=True, check=True
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    return out.stdout.strip()


def questions_provenance(path):
    """Identify the exact question text used, so a score can be traced back to it.

    `blob_sha` is the content hash of the file as it sits on disk — it is what makes the run
    reproducible, and it is computed from the working copy rather than from HEAD so that a run
    against an edited file is still identified honestly. `uncommitted` records whether that
    content is in git at all: a run against an uncommitted question set is legitimate while
    iterating, but a run record that does not say so is not.

    Both failure modes are refused rather than papered over. A questions file outside the repo has
    no provenance to record, and a `git` that cannot answer would leave a null sha and an
    `uncommitted: false` that reads as "clean" when it means "unknown" — a run record asserting
    provenance it does not have is worse than no run at all, which is the whole point of #1671.
    """
    try:
        rel = path.relative_to(ROOT).as_posix()
    except ValueError:
        sys.exit(
            f"questions file must live inside the repo so a run can be traced back to it:\n"
            f"  {path}\nis outside\n  {ROOT}"
        )
    blob_sha = git("hash-object", str(path))
    commit = git("rev-parse", "HEAD")
    # "" means a clean working tree; None means the command itself failed.
    dirty = git("status", "--porcelain", "--", rel)
    if blob_sha is None or commit is None or dirty is None:
        sys.exit(
            "cannot record question provenance: git is unavailable or this is not a git checkout."
            " A run whose questions cannot be identified is not reproducible, so it is refused"
            " rather than recorded with a null sha."
        )
    return {
        "path": rel,
        "blob_sha": blob_sha,
        "commit": commit,
        "uncommitted": bool(dirty),
    }


def select(questions, only, include_excluded):
    if only:
        wanted = {q.strip() for q in only.split(",") if q.strip()}
        selected = [q for q in questions if q["fixture_id"] in wanted]
        missing = wanted - {q["fixture_id"] for q in selected}
        if missing:
            sys.exit(f"unknown fixture_id(s): {', '.join(sorted(missing))}")
        return selected
    if include_excluded:
        return list(questions)
    return [q for q in questions if q["in_chat_path_gate"]]


def ask(url, token, api_version, message, timeout):
    body = json.dumps({"message": message}).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "application/json")
    request.add_header("Authorization", f"Bearer {token}")
    request.add_header("X-API-Version", api_version)
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
        return {"answer": payload.get("response", ""), "error": None,
                "elapsed_s": round(time.monotonic() - started, 1)}
    except urllib.error.HTTPError as exc:
        # The body of a failed turn carries the ApiError envelope, which is the useful half of the
        # evidence — a bare status code cannot tell a permission degradation from a tool failure.
        detail = exc.read().decode("utf-8", errors="replace")[:2000]
        return {"answer": "", "error": f"HTTP {exc.code}: {detail}",
                "elapsed_s": round(time.monotonic() - started, 1)}
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        return {"answer": "", "error": f"{type(exc).__name__}: {exc}",
                "elapsed_s": round(time.monotonic() - started, 1)}


def check_expected_plan(expected_plan, tool_calls):
    """Compare an observed tool-call count against QUESTIONS.json's `expected_plan`, if any.

    Returns a note string when the check ran (pass or fail), or None when there was nothing to
    check — no `expected_plan` on the question, or `tool_calls` not observable (#1676: true for
    every run today, since neither the chat response nor an admin endpoint exposes it). A non-None,
    non-"OK" note only ever happens once `tool_calls` stops being None, which nothing in this script
    currently does — see the module docstring.
    """
    if not expected_plan:
        return None
    if tool_calls is None:
        return "not checked: tool calls are not observable on this endpoint (see script docstring)"
    shortfalls = []
    for tool, minimum in expected_plan.get("min_tool_calls", {}).items():
        observed = tool_calls.get(tool, 0)
        if observed < minimum:
            shortfalls.append(f"{tool}×{observed} < required {minimum}")
    if shortfalls:
        reason = expected_plan.get("declined_reason", "declined composition")
        return f"FAIL — {reason} ({'; '.join(shortfalls)})"
    return "OK — met " + ", ".join(
        f"{tool}×{minimum}" for tool, minimum in expected_plan.get("min_tool_calls", {}).items()
    )


def format_tool_calls(tool_calls):
    if tool_calls is None:
        return "n/a (not exposed by the endpoint)"
    if not tool_calls:
        return "(none)"
    return ", ".join(f"{tool}×{count}" for tool, count in sorted(tool_calls.items()))


def write_markdown(out_dir, record):
    """Emit the run-document skeleton a grader fills in, with provenance already recorded."""
    provenance = record["questions_file"]
    lines = [
        f"# Analytics gate chat-path run — {record['started_at'][:10]}",
        "",
        f"Questions: `{provenance['path']}` blob `{provenance['blob_sha']}`"
        f" (repo commit `{provenance['commit']}`"
        + (", **uncommitted edits present**" if provenance["uncommitted"] else "")
        + ")",
        "Ground truth: `pos-mcp-server/src/test/resources/eval/analytics-gate/ground-truth/EXPECTED.md`",
        f"Endpoint: `{record['endpoint']}` · questions asked: {len(record['results'])}",
        "",
        "Verdicts are graded by hand against the ground truth (plan §2.1 criterion 1); this file is"
        " generated with the Verdict column empty. Tool calls are not observable on this endpoint"
        " today (#1676 — see script docstring), so that column always reads n/a and a question"
        " with an expected_plan gets a plan_check note instead of an automatic verdict.",
        "",
        "| Q | Verdict | Window the question fixes | Elapsed | Tool calls |",
        "|---|---|---|---|---|",
    ]
    for result in record["results"]:
        window = result["window"]
        lines.append(
            f"| {result['fixture_id']} |  | {window['shape']}, {window['resolved_range']} |"
            f" {result['elapsed_s']}s | {format_tool_calls(result['tool_calls'])} |"
        )
    lines.append("")
    for result in record["results"]:
        lines.append(f"## {result['fixture_id']} — {result['expected_section']}")
        lines.append("")
        lines.append(f"> {result['utterance']}")
        lines.append("")
        if result["plan_check"]:
            lines.append(f"**Composition check ({result['fixture_id']}):** {result['plan_check']}")
            lines.append("")
        lines.append("```")
        lines.append(result["error"] or result["answer"] or "(empty response)")
        lines.append("```")
        lines.append("")
    (out_dir / "run.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", required=True, help="directory for run.json / run.md")
    parser.add_argument("--questions", default=str(QUESTIONS_PATH))
    parser.add_argument("--url", default=os.environ.get("MCP_CHAT_URL", DEFAULT_URL))
    parser.add_argument("--token", default=None, help="bearer token; prefer MCP_BEARER_TOKEN")
    parser.add_argument("--env-file", default=None, help="KEY=VALUE file to read config from")
    parser.add_argument("--only", default=None, help="comma-separated fixture ids, e.g. q09,q12")
    parser.add_argument("--all", action="store_true", help="also ask the excluded questions")
    parser.add_argument("--timeout", type=int, default=180, help="per-turn timeout, seconds")
    args = parser.parse_args()

    if args.env_file:
        load_env_file(args.env_file)
    token = args.token or os.environ.get("MCP_BEARER_TOKEN")
    if not token:
        sys.exit("no bearer token: pass --token or set MCP_BEARER_TOKEN (or --env-file)")

    questions_path = Path(args.questions).resolve()
    document = json.loads(questions_path.read_text(encoding="utf-8"))
    selected = select(document["questions"], args.only, args.all)
    if not selected:
        sys.exit("no questions selected")

    # Provenance first: a run that cannot be traced back to its questions is refused, and
    # refusing before the output directory exists leaves nothing half-written behind.
    provenance = questions_provenance(questions_path)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    record = {
        "started_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "endpoint": args.url,
        "eval_as_of": document.get("eval_as_of"),
        "questions_file": provenance,
        "results": [],
    }
    if record["questions_file"]["uncommitted"]:
        print(f"WARNING: {record['questions_file']['path']} has uncommitted edits;"
              " this run is not reproducible from a commit", file=sys.stderr)

    api_version = os.environ.get("MCP_API_VERSION", "1")
    for question in selected:
        print(f"{question['fixture_id']} …", flush=True)
        outcome = ask(args.url, token, api_version, question["utterance"], args.timeout)
        # tool_calls is always None today — see the module docstring's #1676 note on why nothing
        # observable exists yet; check_expected_plan degrades to a "not checked" note in that case.
        tool_calls = None
        expected_plan = question.get("expected_plan")
        plan_check = check_expected_plan(expected_plan, tool_calls)
        record["results"].append({
            "fixture_id": question["fixture_id"],
            "expected_section": question["expected_section"],
            "ground_truth_sql": question["ground_truth_sql"],
            "utterance": question["utterance"],
            "window": question["window"],
            "expected_plan": expected_plan,
            "tool_calls": tool_calls,
            "plan_check": plan_check,
            **outcome,
        })
        print(f"  {outcome['elapsed_s']}s"
              + (f" — {outcome['error']}" if outcome["error"] else "")
              + (f" — {plan_check}" if plan_check and plan_check.startswith("FAIL") else ""),
              flush=True)

    (out_dir / "run.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    write_markdown(out_dir, record)
    print(f"\nwrote {out_dir / 'run.json'} and {out_dir / 'run.md'}")
    print(f"questions blob sha: {record['questions_file']['blob_sha']}")


if __name__ == "__main__":
    main()
