#!/usr/bin/env python3
"""Chat-path analytics gate runner (#1671, #1682).

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

Live endpoint scoring remains a human judgement against the ground-truth scripts. An optional
machine-readable replay report can provide the observed routing and tool-call trace; when supplied,
the question's `expected` block is graded deterministically without an LLM judge.

The live chat endpoint does not expose tool calls, so live records retain `tool_calls: null` and the
existing n/a composition note. Replay mode reads ordered calls from the report, activates
`check_expected_plan`, and records per-axis pass/fail details plus an aggregate outcome and verdict.

Usage:
    scripts/alpha-itest-tunnel.sh                      # SSM port-forward, if running against alpha
    python3 scripts/analytics_gate_run.py --out /tmp/gaterun3
    python3 scripts/analytics_gate_run.py --out /tmp/q09 --only q09,q12
    python3 scripts/analytics_gate_run.py --out /tmp/all --all      # includes excluded questions
    python3 scripts/analytics_gate_run.py --out /tmp/replay --replay-report report.json

Config (env, or a --env-file in KEY=VALUE form — the itest credentials file is the usual source):
    MCP_CHAT_URL        default http://localhost:18086/mcp-server/v1/mcp/chat
    MCP_BEARER_TOKEN    bearer token for the calling actor (required unless --token is given)
                        Mint it for admin.alpha — the ITEST_USERNAME/ITEST_PASSWORD pair in the
                        itest credentials file. The corpus spans workorder, invoice and A/P
                        questions and no role-scoped actor holds codes across all three; the run
                        aborts if the token's role is not --expect-role (default ROLE_ADMIN).
    MCP_EXPECTED_ROLE   override the expected role; empty string disables the check
    MCP_API_VERSION     default 1, sent as X-API-Version when going through the gateway

Credentials are read from the environment or the env file and are never printed or written into the
run record. Stdlib only.
"""

import argparse
import base64
from collections import Counter
from decimal import Decimal, InvalidOperation
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
QUESTIONS_PATH = ROOT / "pos-mcp-server/src/test/resources/eval/analytics-gate/QUESTIONS.json"
DEFAULT_URL = "http://localhost:18086/mcp-server/v1/mcp/chat"
OUTCOMES = {
    "answered-correctly",
    "asked-appropriately",
    "declined-appropriately",
    "failed",
}
AXES = (
    "intent",
    "tool_selection",
    "argument_accuracy",
    "tool_call_sequence",
    "aggregation",
    "final_answer",
)
UUID_PATTERN = re.compile(
    r"(?<![0-9A-Fa-f])"
    r"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"
    r"(?![0-9A-Fa-f])"
)
NUMBER_PATTERN = re.compile(r"(?<![\w-])[-+]?\$?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?")


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



# The corpus spans workorder labor, invoice revenue and A/P vendor spend, so the caller needs
# permission codes across all three domains. No single role-scoped seeded actor holds that set:
# ROLE_CONTROLLER and ROLE_ACCOUNT_MANAGER answer workorder and A/R questions but deflect on A/P
# vendor spend, and ROLE_LOCATION_MANAGER is offered location tools and deflects on everything the
# corpus asks. `admin.alpha` (ROLE_ADMIN) is the actor the gate is written for — and it is the
# ITEST_USERNAME/ITEST_PASSWORD pair in the itest credentials file, so the default env-file
# credentials were always correct; the 2026-09-04 void runs came from reaching past them for a
# role-specific actor (#1706).
EXPECTED_ROLE_DEFAULT = "ROLE_ADMIN"


def actor_provenance(token):
    """The calling actor's identity, read from the bearer token's own claims.

    A gate score is meaningless without this. Two runs minutes apart on 2026-09-04, same
    questions blob and same endpoint, scored 0/12 and 2/12 purely because the first used a
    location manager and the second a controller — and neither run record said so, so the first
    looked like twelve model failures rather than a void run (#1706).

    Only non-secret claims are read: the subject, the roles, and the LENGTH of the permission
    bitset. The token itself is never returned, logged or written to the record, which keeps the
    existing "credentials are never printed or written into the run record" guarantee intact — a
    role name is not a credential.

    Returns a dict with `error` set rather than raising, so the caller decides what an
    unreadable token means. `main` treats it as a failed preflight and refuses to start: the
    point of recording the actor is to know who asked, and "could not tell" is not an answer
    that makes a score comparable. The error is still recorded rather than only printed, so a
    run aborted this way says why in the same place a completed run says who.
    """
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        claims = json.loads(base64.urlsafe_b64decode(payload))
    except Exception as exc:  # noqa: BLE001 - any malformed token lands here, all handled alike
        return {"error": f"could not decode the bearer token's claims: {type(exc).__name__}"}

    roles = claims.get("role") or claims.get("roles") or []
    if isinstance(roles, str):
        roles = [roles]
    # ROLE_FACTOR_PASSWORD is an authentication-factor marker every seeded actor carries; it says
    # nothing about what the caller may reach, so it is recorded but never treated as the role.
    effective = [r for r in roles if r != "ROLE_FACTOR_PASSWORD"]
    # Deliberately NOT the encoded bitset's length: pos-security-service issues `perm_bits` as a
    # Base64URL BitSet, and BitSet.toByteArray drops trailing zero bytes, so the length tracks the
    # highest set bit index rather than how many codes were granted. One high-index permission
    # encodes longer than fifty low-index ones, which makes it useless as coverage provenance.
    # `perm_ver` is recorded instead: without the catalog version a role name cannot be
    # interpreted across catalog changes.
    return {
        "subject": claims.get("sub") or claims.get("username"),
        "roles": roles,
        "effective_roles": effective,
        "permission_catalog_version": claims.get("perm_ver"),
    }



# DateWindowResolver.statement() prefixes every window with its shape label, and the DATE_WINDOW
# contract requires the model to quote that statement in the answer. That makes the shape readable
# from the reply itself, with no production change — which is what lets the gate grade the SHAPE
# rather than the endpoints (#1709 option 3).

# Assistant output is typographically formatted: dates arrive as "2026\u201109\u201104" with U+2011
# NON-BREAKING HYPHEN, spaces as U+202F NARROW NO-BREAK SPACE, and em/en dashes in place of the
# resolver's ASCII separator. Grading against the raw string made two correct answers (q05, q13)
# grade FAIL on 2026-09-04 — a grader defect that reads as a model failure, which is precisely the
# confusion this whole line of work exists to remove.
_TYPOGRAPHIC = {
    "\u2010": "-", "\u2011": "-", "\u2012": "-", "\u2013": "-", "\u2014": "-", "\u2212": "-",
    "\u00a0": " ", "\u202f": " ", "\u2009": " ", "\u2007": " ",
    "\u2018": "'", "\u2019": "'", "\u201c": '"', "\u201d": '"',
}


def normalise_typography(text):
    """Folds typographic punctuation to ASCII so grading reads what the model meant."""
    if not text:
        return text
    return "".join(_TYPOGRAPHIC.get(ch, ch) for ch in text)


_STATEMENT_LABELS = {
    "rolling": "ROLLING",
    "current to date": "CURRENT_TO_DATE",
    "prior complete": "PRIOR_COMPLETE",
    "calendar span": "CALENDAR_SPAN",
    "next": "FORWARD",
    "absolute": "ABSOLUTE",
}
# The comparison window carries its own label, so a question expecting one can check the model
# actually resolved it rather than only that it picked the right primary shape.
_COMPARISON_LABELS = {"prior period": "PRIOR_PERIOD", "year earlier": "YEAR_EARLIER"}

_ALL_LABELS = {**_STATEMENT_LABELS, **_COMPARISON_LABELS}
_LABEL_ALTERNATION = "|".join(sorted(_ALL_LABELS, key=len, reverse=True))
_STATEMENT_RE = re.compile(
    r"\b(" + _LABEL_ALTERNATION + r")\s*:\s*"
    r"(\d{4}-\d{2}-\d{2})\s+to\s+(\d{4}-\d{2}-\d{2})\s*[^A-Za-z0-9]*"
    # Non-greedy, stopping at the next label: a greedy clause ran to end of line and swallowed any
    # further statement on it, so a multi-window answer reported only its first window.
    r"(.*?)(?=\b(?:" + _LABEL_ALTERNATION + r")\s*:|$)",
    re.IGNORECASE | re.MULTILINE,
)
_UNIT_RE = re.compile(r"\b(day|week|month|quarter|year)s?\b", re.IGNORECASE)
_COUNT_RE = re.compile(r"(?<![\d-])(\d+)\s+(?:whole\s+)?(?:day|week|month|quarter|year)s?\b", re.IGNORECASE)


def parse_statements(answer):
    """Every resolver statement the answer quotes, as {shape, unit, count} dicts, in order.

    The clause after the dates carries the unit and (for the multi-period shapes) the count —
    "6 whole months ending with the last complete month" — so the triple the #1709 decision asks
    for is readable from the same string the shape came from.
    """
    if not answer:
        return []
    parsed = []
    for match in _STATEMENT_RE.finditer(normalise_typography(answer)):
        label, _start, _end, clause = match.groups()
        unit = _UNIT_RE.search(clause)
        count = _COUNT_RE.search(clause)
        parsed.append(
            {
                "shape": _ALL_LABELS[label.lower()],
                "unit": unit.group(1).upper() if unit else None,
                # A shape that names exactly one period states no number; treat that as count 1
                # rather than unknown, which is what PRIOR_COMPLETE and CURRENT_TO_DATE mean.
                "count": int(count.group(1))
                if count
                else (1 if _ALL_LABELS[label.lower()] in {"PRIOR_COMPLETE", "CURRENT_TO_DATE"} else None),
            }
        )
    return parsed


def observed_shapes(answer):
    """Primary shapes quoted by the answer, de-duplicated, comparison labels excluded."""
    seen = []
    for statement in parse_statements(answer):
        if statement["shape"] in _STATEMENT_LABELS.values() and statement["shape"] not in seen:
            seen.append(statement["shape"])
    return seen



# Phrases that mark a reply as a clarifying question or a refusal. Deliberately conservative: a
# false "declined" would score a real answer as a correct refusal, which is the one mistake that
# makes the impossible band worthless.
_ASK_MARKERS = (
    "which metric", "which measure", "please tell me which", "could you clarify",
    "isn't defined in the business glossary", "is not defined in the business glossary",
    "i need the exact", "please confirm", "do you mean",
)
_DECLINE_MARKERS = (
    "i'm unable", "i am unable", "unable to", "cannot answer", "can't answer",
    "couldn't find a way", "could not find a way", "does not expose", "doesn't expose",
    "no tool", "not supported",
)


def classify_outcome(answer):
    """Classifies a reply as `asked`, `declined` or `answered` (#1689).

    Order matters: a refusal often ends by offering alternatives phrased as a question, so the ask
    markers are checked first only where they are unambiguous. Anything not clearly one of the two
    is `answered`, because scoring a genuine answer as a refusal would quietly turn the impossible
    band into a band that passes on failure.
    """
    if not answer or not answer.strip():
        return "empty"
    # Shared with the window grader: assistant output is typographically formatted, so
    # "isn't defined" written with U+2019 would otherwise miss its marker. #1689 and #1709 landed
    # in parallel each with its own fold; this is the unification both promised.
    text = normalise_typography(answer).lower()
    if any(marker in text for marker in _ASK_MARKERS):
        return "asked"
    if any(marker in text for marker in _DECLINE_MARKERS):
        return "declined"
    return "answered"



# ── window shape from the recorded tool trace (#1743) ────────────────────────
#
# The grader used to read the shape out of the model's ANSWER, on the assumption that the
# DATE_WINDOW contract to quote the resolver statement holds. On 2026-09-04 it did not: six of
# twelve questions quoted nothing while the service log showed sixteen correct resolver calls. The
# shape was resolved, recorded, and unreadable by the thing grading it.
#
# The tool trace carries it at the tool boundary instead, which is where it is a fact rather than a
# disclosure the model may or may not make.

TRACE_PATH = "/v1/eval/turn-traces"
_WINDOW_TOOLS = {"resolveDateWindow", "resolvenamedperiod", "resolvedatewindow", "resolveNamedPeriod"}


def traces_url_for(chat_url):
    """The turn-trace endpoint on the same host and context path as the chat endpoint."""
    marker = "/v1/mcp/chat"
    if chat_url.endswith(marker):
        return chat_url[: -len(marker)] + TRACE_PATH
    return chat_url.rsplit("/v1/", 1)[0] + TRACE_PATH


def fetch_traces(traces_url, token, since, timeout=60):
    """The caller's traces since `since`, or [] with a reason when unavailable.

    Never raises: a run whose traces cannot be fetched must still grade from answers rather than
    dying. The reason is returned so the record can say WHY it fell back, instead of a reader having
    to guess whether the endpoint is missing, forbidden, or simply empty.
    """
    # urlencode, and Z rather than +00:00: an unencoded "+" decodes to a space, so Spring would
    # fail to parse @RequestParam Instant and answer 400 — and the runner would silently fall back
    # to answer parsing on every run, exactly the behaviour this change exists to replace.
    query = urllib.parse.urlencode(
        {"since": since.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "limit": 200}
    )
    request = urllib.request.Request(f"{traces_url}?{query}")
    request.add_header("Authorization", f"Bearer {token}")
    request.add_header("X-API-Version", os.environ.get("MCP_API_VERSION", "1"))
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8")), None
    except urllib.error.HTTPError as error:
        return [], f"HTTP {error.code} from {traces_url}"
    except Exception as error:  # noqa: BLE001 - any transport failure falls back identically
        return [], f"{type(error).__name__} from {traces_url}"


def _span_count(result, unit):
    """How many whole `unit` periods the resolved window covers, from its own dates.

    The resolver's result carries startDate/endDate but not unit or count, so this is the only
    evidence available about a corrected window's length. Returns None when it cannot be derived,
    which the caller treats as "unknown" rather than "matches".

    "Whole" is enforced, not assumed: a calendar unit only yields a count when the window actually
    starts on a period boundary and ends on the day before the next one. A partial span returns
    None and therefore cannot match an expected count — if the resolver ever produced a
    part-month CALENDAR_SPAN, counting its months inclusively would hand back the expected number
    and PASS a broken window.
    """
    start, end = result.get("startDate"), result.get("endDate")
    if not start or not end or not unit:
        return None
    try:
        s_d = datetime.strptime(start, "%Y-%m-%d").date()
        e_d = datetime.strptime(end, "%Y-%m-%d").date()
    except (TypeError, ValueError):
        return None
    if e_d < s_d:
        return None
    if unit in {"MONTH", "QUARTER", "YEAR"}:
        # Must span whole calendar months: first day of one, last day of another.
        if s_d.day != 1 or (e_d + timedelta(days=1)).day != 1:
            return None
        months = (e_d.year - s_d.year) * 12 + (e_d.month - s_d.month) + 1
        if unit == "MONTH":
            return months
        if unit == "QUARTER":
            return months // 3 if months % 3 == 0 else None
        return months // 12 if months % 12 == 0 else None
    if unit in {"DAY", "WEEK"}:
        days = (e_d - s_d).days + 1
        return days if unit == "DAY" else (days // 7 if days % 7 == 0 else None)
    return None


def window_from_trace(trace):
    """The window a turn actually resolved, read from its recorded tool calls.

    Reads each call's RESULT, not its arguments. Those were the same thing until #1675: the model
    named a shape and the resolver computed it. The server now corrects a shape the wording
    contradicts, so the argument is what was asked for and the result is what the downstream query
    actually used — and only the latter is what the corpus means.

    Grading the argument made a working fix look broken: on 2026-09-05 the server resolved q12 and
    q15 as CALENDAR_SPAN while the model had asked for ROLLING, and the gate reported FAIL on both.
    It fails the other way too, and worse — a correction that silently stopped working would still
    read as PASS whenever the model happened to send the right shape.

    `model_shape` is kept alongside so a divergence stays visible rather than being smoothed away;
    the model's own classification is still worth knowing, it is just not the thing under test.

    Returns a list of {shape, unit, count, comparison, model_shape, failed} in call order. A
    bucketed question resolves several, so this keeps them all and lets the caller decide what
    satisfies the expectation.

    `failed` marks a resolver call that threw: its other fields are all None, because no window was
    resolved. Callers must check it — treating such an entry as a resolved window is what let a
    failed call grade PASS.
    """
    resolved = []
    for call in trace.get("toolCalls") or []:
        name = (call.get("name") or "").lower()
        if name not in {tool.lower() for tool in _WINDOW_TOOLS}:
            continue
        # A resolver that threw produced NO window: ToolInvocationRecorder records it as
        # result=null with `error` set. Falling back to the arguments here would grade the model's
        # request as though it had been resolved — so a call that failed outright would PASS
        # whenever the model happened to ask for the right shape. Marked rather than dropped,
        # because a turn whose resolver failed must not read as "no window expectation".
        if call.get("error"):
            resolved.append({
                "shape": None,
                "unit": None,
                "count": None,
                "comparison": None,
                "model_shape": None,
                "failed": True,
            })
            continue
        try:
            arguments = json.loads(call.get("arguments") or "{}")
        except (TypeError, ValueError):
            continue
        try:
            result = json.loads(call.get("result") or "{}")
        except (TypeError, ValueError):
            result = {}
        if not isinstance(result, dict):
            result = {}

        if "period" in arguments and "shape" not in arguments:
            # resolveNamedPeriod names an absolute period rather than a relative shape.
            resolved.append({
                "shape": "ABSOLUTE",
                "unit": None,
                "count": None,
                "comparison": None,
                "model_shape": None,
                "failed": False,
            })
            continue

        model_shape = arguments.get("shape")
        # The result is authoritative; the argument is the fallback for a trace recorded before
        # #1675, or a call whose result did not parse.
        shape = result.get("shape") or model_shape
        if not shape:
            continue
        count = arguments.get("count")
        count = int(count) if isinstance(count, (int, str)) and str(count).isdigit() else None
        unit = str(arguments["unit"]).upper() if arguments.get("unit") else None
        corrected = bool(model_shape) and str(model_shape).upper() != str(shape).upper()
        if corrected:
            # The same classifier that corrected the shape also sets unit and count, and the result
            # JSON carries neither — only dates. Keeping the requested unit/count here would grade a
            # corrected window against the request that was overridden, turning a regression the old
            # code caught loudly into a silent PASS. Derive what the dates prove; leave the rest
            # None so it cannot match instead of matching wrongly.
            count = _span_count(result, unit) or None
        resolved.append(
            {
                "shape": str(shape).upper(),
                "unit": unit,
                "count": count,
                "comparison": str(arguments["comparison"]).upper() if arguments.get("comparison") else None,
                "model_shape": str(model_shape).upper() if model_shape else None,
                "failed": False,
            }
        )
    return resolved


def grade_window(question, answer, as_of, resolved=None):
    """Grades the window a question was answered on.

    Endpoints are deliberately not compared for relative windows. They are a derived consequence of
    (shape, unit, count) and the run's own date, so comparing them to dates baked from a fixed
    `eval_as_of` fails every run that does not execute on that exact day — which is the defect this
    replaces (#1709). Where an endpoint genuinely matters and no resolver shape describes it
    (point-in-time questions), it is expressed as an offset from the run's as-of date instead.

    Returns (verdict, detail) where verdict is PASS / FAIL / UNGRADED.
    """
    expected = (question.get("window") or {}).get("expected") or {}
    wanted = expected.get("shape")

    if wanted is None:
        if "as_of_offset_days" in expected and as_of is not None:
            if not answer:
                # A transport failure is not a window failure. Keeping them separable is the whole
                # point of grading per stage.
                return "UNGRADED", "[answer] no answer to read an as-of date from"
            target = as_of + timedelta(days=int(expected["as_of_offset_days"]))
            if target.isoformat() in normalise_typography(answer):
                return "PASS", f"answer states the as-of date {target.isoformat()}"
            return "FAIL", f"expected the as-of date {target.isoformat()} to appear in the answer"
        return "UNGRADED", "[corpus] " + (expected.get("note") or "no window expectation recorded")

    if resolved is not None:
        statements = resolved
        failed = [s for s in statements if s.get("failed")]
        if failed:
            # The resolver threw, so no window reached the downstream query. UNGRADED would be the
            # softer verdict, but the run summary only fails on FAIL — a broken resolver would then
            # still produce a green run, which is the outcome this harness exists to prevent.
            return "FAIL", (
                f"[trace] {len(failed)} window-resolver call(s) failed, so no window was resolved "
                "for this turn"
            )
        observed = []
        for statement in resolved:
            if statement["shape"] in _STATEMENT_LABELS.values() and statement["shape"] not in observed:
                observed.append(statement["shape"])
        source = "trace"
    else:
        statements = parse_statements(answer)
        observed = observed_shapes(answer)
        source = "answer"
    if not observed:
        return "UNGRADED", (
            "[trace] no window was resolved in the tool trace for this turn"
            if source == "trace"
            else "[answer] the answer quotes no resolver statement and no tool trace was available, "
            "so the shape cannot be read (the DATE_WINDOW contract requires quoting it)"
        )

    acceptable = {wanted, *expected.get("also_accept", [])}
    matching = [s for s in statements if s["shape"] in acceptable]
    if not matching:
        return "FAIL", f"[{source}] expected {wanted}, observed {observed}"

    # Shape alone is not the decision recorded on #1709 — it asks for the triple. A question
    # answered on ONE calendar month where six were specified is the wrong window, and grading only
    # the label would call it a pass.
    problems = []
    want_unit = expected.get("unit")
    if want_unit and not any(statement["unit"] == want_unit.upper() for statement in matching):
        problems.append(f"unit: expected {want_unit.upper()}, answer quotes {[s['unit'] for s in matching]}")

    want_count = expected.get("count")
    if want_count is not None:
        absolute = [s for s in matching if s["shape"] == "ABSOLUTE"]
        if absolute and len(absolute) == len(matching):
            # A named period states no count; for a bucketed question the count is how many were
            # resolved. One ABSOLUTE month does not satisfy "six months" — that was q04's actual
            # under-answer on the 2026-09-04 run.
            if len(absolute) != want_count:
                problems.append(
                    f"count: expected {want_count} periods, answer quotes {len(absolute)} ABSOLUTE window(s)"
                )
        elif not any(statement["count"] == want_count for statement in matching):
            problems.append(f"count: expected {want_count}, answer quotes {[s['count'] for s in matching]}")

    wanted_comparison = expected.get("comparison")
    if wanted_comparison and wanted_comparison != "NONE":
        # The answer path emits comparison labels in `shape` (parse_statements merges both label
        # maps); the trace path keeps them in their own key. Checking only `shape` meant a
        # trace-graded question could never satisfy an expected comparison — q15 is the only
        # question that declares one, and it failed on this whatever its shape resolved to.
        if not any(
            s.get("shape") == wanted_comparison or s.get("comparison") == wanted_comparison for s in statements
        ):
            problems.append(f"comparison: expected a {wanted_comparison} window, answer quotes none")

    if problems:
        return "FAIL", f"[{source}] shape {wanted} matched, but " + "; ".join(problems)
    return "PASS", f"[{source}] expected {wanted}, observed {observed}"


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



# ── behaviour bands and multi-turn sequences (#1688, #1689, #1690) ───────────
#
# Both corpora shipped as data with nothing to execute them. A fixture nothing runs is
# documentation, so these are the two run modes that turn them into measurements.

BANDS_PATH = "pos-mcp-server/src/test/resources/eval/analytics-gate/BEHAVIOUR_BANDS.json"
SEQUENCES_PATH = "pos-mcp-server/src/test/resources/eval/analytics-gate/MULTI_TURN.json"


def run_bands(url, token, api_version, document, timeout):
    """Asks each band question once and scores the OUTCOME, not the content.

    An impossible question is passed by a refusal and failed by an answer, however plausible that
    answer looks — which is the whole reason the band exists. Until it did, a correct refusal and a
    genuine failure scored identically.
    """
    results = []
    for question in document["questions"]:
        outcome = ask(
            url, token, api_version, question["utterance"], timeout, conversation_id=conversation_id_for(question)
        )
        observed = classify_outcome(outcome["answer"])
        expected = question["expected_outcome"]
        results.append(
            {
                "fixture_id": question["fixture_id"],
                "band": question["band"],
                "utterance": question["utterance"],
                "expected_outcome": expected,
                "observed_outcome": observed,
                "verdict": "PASS" if observed == expected else "FAIL",
                **outcome,
            }
        )
        print(f"  {question['fixture_id']} {question['band']}: expected {expected}, got {observed}", flush=True)
    return results


def run_sequences(url, token, api_version, document, timeout):
    """Runs each sequence's turns in order on one conversation.

    Each sequence names its own conversation, so its turns share a memory and different sequences
    do not bleed into each other. Before #1735 no identifier was possible — the server keyed memory
    on (username, role) alone, which gave these sequences the continuity they need by accident and
    gave the single-turn gate a continuity it must not have.

    Turns are recorded, not auto-scored. Whether "their" resolved to the right customer is a
    judgement, and a regex over the answer would pin the wrong thing — so each turn carries its
    fixture's `must_reference` and `fails_if` alongside the reply for a grader to apply.
    """
    results = []
    for sequence in document["sequences"]:
        turns = []
        sequence_conversation_id = f"seq-{sequence['sequence_id']}"
        for index, turn in enumerate(sequence["turns"]):
            outcome = ask(
                url, token, api_version, turn["utterance"], timeout, conversation_id=sequence_conversation_id
            )
            turns.append(
                {
                    "index": index + 1,
                    "utterance": turn["utterance"],
                    "expect": turn.get("expect"),
                    "must_reference": turn.get("must_reference"),
                    "fails_if": turn.get("fails_if"),
                    "observed_outcome": classify_outcome(outcome["answer"]),
                    **outcome,
                }
            )
            print(f"  {sequence['sequence_id']} turn {index + 1}: {turns[-1]['observed_outcome']}", flush=True)
        results.append({"sequence_id": sequence["sequence_id"], "carries": sequence["carries"], "turns": turns})
    return results


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


def turn_conversation_id(args, fixture_id):
    """The conversation a single-turn question belongs to, or None for the shared server default.

    On by default since #1757 deployed. It was opt-in only while the server could not accept a
    conversationId, because a transport error on every question would have been worse than the
    shared-memory bias it corrects; that reason expired when alpha took the field.

    Isolation is what makes a run reproducible, which is the property everything downstream needs.
    Sharing one conversation across the corpus let question N be shaped by the N-1 before it: on
    2026-09-05 the same build answered q05 correctly under isolation and deflected it under the
    shared key, in runs minutes apart. A gate whose number moves with question order cannot be
    compared against another build, another model, or its own previous run.

    --no-isolate-turns restores the shared key. It exists to reproduce a historical run or to
    measure the sharing effect deliberately, not as a fallback.
    """
    if not getattr(args, "isolate_turns", True):
        return None
    return f"{args.run_id}-{fixture_id}"


def conversation_id_for(question):
    """Band fixtures are independent single-turn probes, so each gets its own conversation."""
    return f"bands-{question['fixture_id']}"


def ask(url, token, api_version, message, timeout, conversation_id=None):
    """Sends one turn.

    #1735: the server keys conversation memory on (username, role) alone unless the request names
    a conversation, so twelve questions asked by one actor land in one twelve-turn history and no
    question in this corpus is the independent single-turn test it is written as. Passing a
    distinct conversation_id per question is what makes them independent.

    The field is only sent when a caller asks for it, because a server that predates #1757 does not
    know it and may reject the body outright — which would turn every question into a transport
    error rather than an answer.
    """
    payload = {"message": message}
    if conversation_id is not None:
        payload["conversationId"] = conversation_id
    body = json.dumps(payload).encode("utf-8")
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
    # OSError covers the connection-level failures urllib raises unwrapped — RemoteDisconnected is a
    # ConnectionResetError and TimeoutError is itself an OSError subclass, so naming either
    # separately would be redundant.
    # ConnectionResetError, not a URLError, so it escaped both handlers and killed the whole run.
    # A tunnel hiccup on question 2 discarded question 1's completed result along with the other
    # ten, which is the opposite of what a harness should do: a transport failure is data about one
    # turn, not grounds for losing the others.
    except (urllib.error.URLError, OSError, json.JSONDecodeError) as exc:
        return {"answer": "", "error": f"{type(exc).__name__}: {exc}",
                "elapsed_s": round(time.monotonic() - started, 1)}


def _axis(passed, details):
    return {"passed": bool(passed), "details": details}


def _field(mapping, *names, default=None):
    for name in names:
        if name in mapping:
            return mapping[name]
    return default


def _normalize_arguments(fixture_id, call_index, arguments):
    if isinstance(arguments, str):
        try:
            arguments = json.loads(arguments)
        except json.JSONDecodeError as exc:
            raise ValueError(
                f"fixture {fixture_id} tool call {call_index} arguments must be valid JSON:"
                f" {exc.msg}"
            ) from exc
    if not isinstance(arguments, dict):
        raise ValueError(
            f"fixture {fixture_id} tool call {call_index} arguments must be a JSON object"
        )
    return arguments


def normalize_replay_entry(fixture_id, entry):
    """Normalize one snake_case or EvalTurnTrace-shaped report entry."""
    if not isinstance(entry, dict):
        raise ValueError(f"fixture {fixture_id} report entry must be a JSON object")
    wrapped = _field(entry, "observed", "trace")
    if wrapped is not None:
        if not isinstance(wrapped, dict):
            raise ValueError(f"fixture {fixture_id} observed trace must be a JSON object")
        entry = wrapped

    raw_calls = _field(entry, "tool_calls", "toolCalls", default=[])
    if not isinstance(raw_calls, list):
        raise ValueError(f"fixture {fixture_id} tool_calls must be a JSON array")
    calls = []
    for index, raw_call in enumerate(raw_calls, start=1):
        if not isinstance(raw_call, dict):
            raise ValueError(f"fixture {fixture_id} tool call {index} must be a JSON object")
        name = _field(raw_call, "name", "tool_name", "toolName")
        if not isinstance(name, str) or not name.strip():
            raise ValueError(
                f"fixture {fixture_id} tool call {index} name must be a non-empty string"
            )
        sequence = raw_call.get("sequence", index)
        if not isinstance(sequence, int) or isinstance(sequence, bool) or sequence != index:
            raise ValueError(
                f"fixture {fixture_id} tool call {index} sequence must be the ordered value {index}"
            )
        calls.append({
            "sequence": sequence,
            "name": name,
            "arguments": _normalize_arguments(fixture_id, index, raw_call.get("arguments", {})),
            "result": raw_call.get("result"),
            "error": raw_call.get("error"),
            "elapsed_ms": _field(raw_call, "elapsed_ms", "elapsedMs"),
        })

    intent = entry.get("intent")
    tier = _field(entry, "tier", "model_tier", "modelTier")
    answer = _field(entry, "answer", "response", "final_response", "finalResponse", default="")
    error = entry.get("error")
    if intent is not None and not isinstance(intent, str):
        raise ValueError(f"fixture {fixture_id} intent must be a string or null")
    if tier is not None and not isinstance(tier, str):
        raise ValueError(f"fixture {fixture_id} tier must be a string or null")
    if answer is None:
        answer = ""
    if not isinstance(answer, str):
        raise ValueError(f"fixture {fixture_id} response must be a string or null")
    if error is not None and not isinstance(error, str):
        raise ValueError(f"fixture {fixture_id} error must be a string or null")
    return {
        "intent": intent,
        "tier": tier,
        "tool_calls": calls,
        "answer": answer,
        "error": error,
        "elapsed_s": _field(entry, "elapsed_s", "elapsedSeconds", default=0.0),
        "ids": entry.get("ids"),
        "numbers": entry.get("numbers"),
    }


def load_replay_report(path, known_fixture_ids):
    """Read and validate a replay report, returning normalized entries keyed by fixture id."""
    try:
        report = json.loads(Path(path).read_text(encoding="utf-8"))
    except OSError as exc:
        raise ValueError(f"cannot read replay report {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f"replay report {path} is not valid JSON: {exc.msg}") from exc
    if not isinstance(report, dict) or not isinstance(report.get("results"), list):
        raise ValueError("replay report must be a JSON object with a results array")

    by_fixture = {}
    unknown = []
    for index, entry in enumerate(report["results"], start=1):
        if not isinstance(entry, dict):
            raise ValueError(f"replay report entry {index} must be a JSON object")
        fixture_id = entry.get("fixture_id")
        if not isinstance(fixture_id, str) or not fixture_id:
            raise ValueError(f"replay report entry {index} fixture_id must be a non-empty string")
        if fixture_id in by_fixture:
            raise ValueError(f"replay report has duplicate fixture_id: {fixture_id}")
        if fixture_id not in known_fixture_ids:
            unknown.append(fixture_id)
            continue
        by_fixture[fixture_id] = normalize_replay_entry(fixture_id, entry)
    if unknown:
        raise ValueError(f"replay report has unknown fixture_id(s): {', '.join(sorted(unknown))}")
    return by_fixture


def _require_string_list(fixture_id, expected, field):
    values = expected.get(field)
    if values is None:
        return
    if (not isinstance(values, list)
            or any(not isinstance(value, str) or not value for value in values)):
        raise ValueError(f"fixture {fixture_id} expected.{field} must be an array of strings")
    if len(set(values)) != len(values):
        raise ValueError(f"fixture {fixture_id} expected.{field} must not contain duplicates")


def validate_expected(fixture_id, expected):
    """Reject ambiguous expected-block shapes before any grading is attempted."""
    if not isinstance(expected, dict):
        raise ValueError(f"fixture {fixture_id} must have an expected JSON object")
    outcome = expected.get("outcome")
    if outcome not in OUTCOMES:
        raise ValueError(
            f"fixture {fixture_id} expected.outcome must be one of {', '.join(sorted(OUTCOMES))}"
        )
    for field in ("intent", "tier"):
        if field in expected and (not isinstance(expected[field], str) or not expected[field]):
            raise ValueError(f"fixture {fixture_id} expected.{field} must be a non-empty string")
    for field in ("tool_sequence", "id_set", "id_order", "required_strings", "forbidden_strings"):
        _require_string_list(fixture_id, expected, field)

    expected_calls = expected.get("tool_calls")
    if expected_calls is not None:
        if not isinstance(expected_calls, list):
            raise ValueError(f"fixture {fixture_id} expected.tool_calls must be an array")
        for index, call in enumerate(expected_calls, start=1):
            if not isinstance(call, dict):
                raise ValueError(
                    f"fixture {fixture_id} expected.tool_calls[{index}] must be an object"
                )
            if not isinstance(call.get("name"), str) or not call["name"]:
                raise ValueError(
                    f"fixture {fixture_id} expected.tool_calls[{index}].name must be a string"
                )
            if not isinstance(call.get("arguments", {}), dict):
                raise ValueError(
                    f"fixture {fixture_id} expected.tool_calls[{index}].arguments must be an object"
                )
            if call.get("arguments_match", "exact") not in {"exact", "partial"}:
                raise ValueError(
                    f"fixture {fixture_id} expected.tool_calls[{index}].arguments_match"
                    " must be exact or partial"
                )
        sequence = expected.get("tool_sequence")
        call_names = [call["name"] for call in expected_calls]
        if sequence is not None and sequence != call_names:
            raise ValueError(
                f"fixture {fixture_id} expected.tool_sequence must match expected.tool_calls names"
            )

    numbers = expected.get("numbers")
    if numbers is not None:
        if not isinstance(numbers, list):
            raise ValueError(f"fixture {fixture_id} expected.numbers must be an array")
        for index, number in enumerate(numbers, start=1):
            if not isinstance(number, dict) or isinstance(number.get("value"), bool):
                raise ValueError(
                    f"fixture {fixture_id} expected.numbers[{index}] must contain a numeric value"
                )
            try:
                value = Decimal(str(number["value"]))
                tolerance = Decimal(str(number.get("absolute_tolerance", 0)))
            except (KeyError, InvalidOperation, ValueError) as exc:
                raise ValueError(
                    f"fixture {fixture_id} expected.numbers[{index}] value and"
                    " absolute_tolerance must be numeric"
                ) from exc
            if not value.is_finite() or not tolerance.is_finite() or tolerance < 0:
                raise ValueError(
                    f"fixture {fixture_id} expected.numbers[{index}].absolute_tolerance"
                    " must be a finite non-negative number"
                )


def grade_intent(expected, observed):
    checks = []
    if "intent" in expected:
        checks.append(("intent", expected["intent"], observed.get("intent")))
    if "tier" in expected:
        checks.append(("tier", expected["tier"], observed.get("tier")))
    mismatches = [
        f"{field} expected {wanted!r}, observed {actual!r}"
        for field, wanted, actual in checks
        if wanted != actual
    ]
    if mismatches:
        return _axis(False, "; ".join(mismatches))
    if not checks:
        return _axis(True, "not specified")
    return _axis(True, "; ".join(f"{field}={actual}" for field, _, actual in checks))


def _expected_tool_names(expected):
    if "tool_sequence" in expected:
        return expected["tool_sequence"]
    return [call["name"] for call in expected.get("tool_calls", [])]


def grade_tool_selection(expected, observed_calls):
    expected_names = _expected_tool_names(expected)
    if "tool_sequence" not in expected and "tool_calls" not in expected:
        return _axis(True, "not specified")
    observed_names = [call["name"] for call in observed_calls]
    missing = sorted(set(expected_names) - set(observed_names))
    unexpected = sorted(set(observed_names) - set(expected_names))
    if missing or unexpected:
        return _axis(
            False,
            f"missing tools {missing or '[]'}; unexpected tools {unexpected or '[]'}",
        )
    return _axis(True, f"selected {sorted(set(observed_names))}")


def _json_mismatches(expected, observed, partial, path="$"):
    if isinstance(expected, dict):
        if not isinstance(observed, dict):
            return [f"{path} expected object, observed {type(observed).__name__}"]
        missing = sorted(set(expected) - set(observed))
        unexpected = sorted(set(observed) - set(expected)) if not partial else []
        mismatches = []
        if missing:
            mismatches.append(f"{path} missing keys {missing}")
        if unexpected:
            mismatches.append(f"{path} unexpected keys {unexpected}")
        for key in expected.keys() & observed.keys():
            mismatches.extend(
                _json_mismatches(expected[key], observed[key], partial, f"{path}.{key}")
            )
        return mismatches
    if isinstance(expected, list):
        if not isinstance(observed, list):
            return [f"{path} expected array, observed {type(observed).__name__}"]
        if len(expected) != len(observed):
            return [f"{path} expected {len(expected)} items, observed {len(observed)}"]
        mismatches = []
        for index, (wanted, actual) in enumerate(zip(expected, observed)):
            mismatches.extend(_json_mismatches(wanted, actual, False, f"{path}[{index}]"))
        return mismatches
    if expected != observed:
        return [f"{path} expected {expected!r}, observed {observed!r}"]
    return []


def grade_argument_accuracy(expected_calls, observed_calls):
    if expected_calls is None:
        return _axis(True, "not specified")
    if len(expected_calls) != len(observed_calls):
        return _axis(False, f"expected {len(expected_calls)} calls, observed {len(observed_calls)}")
    mismatches = []
    for index, (expected_call, observed_call) in enumerate(
            zip(expected_calls, observed_calls), start=1):
        if expected_call["name"] != observed_call.get("name"):
            mismatches.append(
                f"call {index} expected tool {expected_call['name']!r},"
                f" observed {observed_call.get('name')!r}"
            )
            continue
        partial = expected_call.get("arguments_match", "exact") == "partial"
        for mismatch in _json_mismatches(
                expected_call.get("arguments", {}), observed_call.get("arguments", {}), partial):
            mismatches.append(f"call {index} {mismatch}")
    if mismatches:
        return _axis(False, "; ".join(mismatches))
    return _axis(True, f"matched arguments for {len(expected_calls)} ordered call(s)")


def grade_tool_call_sequence(expected, observed_calls):
    expected_names = _expected_tool_names(expected)
    if "tool_sequence" not in expected and "tool_calls" not in expected:
        return _axis(True, "not specified")
    observed_names = [call["name"] for call in observed_calls]
    if expected_names != observed_names:
        return _axis(False, f"expected {expected_names}, observed {observed_names}")
    return _axis(True, f"matched {observed_names}")


def _extract_numbers(answer):
    numbers = []
    for match in NUMBER_PATTERN.findall(answer):
        try:
            numbers.append(Decimal(match.replace("$", "").replace(",", "")))
        except InvalidOperation:
            continue
    return numbers


def _extract_ids(answer, expected_ids):
    if expected_ids and all(UUID_PATTERN.fullmatch(value) for value in expected_ids):
        return UUID_PATTERN.findall(answer)
    numbered = [re.fullmatch(r"(.*?)(\d+)", value) for value in expected_ids]
    if numbered and all(match and match.group(1) == numbered[0].group(1) for match in numbered):
        prefix = re.escape(numbered[0].group(1))
        return re.findall(rf"(?<!\w){prefix}\d+(?!\w)", answer)
    return [value for value in expected_ids if value in answer]


def grade_aggregation(expected, answer, observed_numbers=None, observed_ids=None):
    failures = []
    checks = 0
    numbers = expected.get("numbers", [])
    if numbers:
        checks += 1
        actual_numbers = (
            [Decimal(str(value)) for value in observed_numbers]
            if observed_numbers is not None
            else _extract_numbers(answer)
        )
        for item in numbers:
            wanted = Decimal(str(item["value"]))
            tolerance = Decimal(str(item.get("absolute_tolerance", 0)))
            if not any(abs(actual - wanted) <= tolerance for actual in actual_numbers):
                failures.append(
                    f"expected number {wanted} within absolute tolerance {tolerance};"
                    f" observed {actual_numbers}"
                )

    expected_set = expected.get("id_set")
    expected_order = expected.get("id_order")
    id_expectations = expected_set or expected_order or []
    actual_ids = (
        list(observed_ids)
        if observed_ids is not None
        else _extract_ids(answer, id_expectations)
    )
    if expected_set is not None:
        checks += 1
        wanted = {value.lower() for value in expected_set}
        actual = {value.lower() for value in actual_ids}
        missing = sorted(wanted - actual)
        unexpected = sorted(actual - wanted)
        if missing or unexpected:
            failures.append(f"missing ids {missing or '[]'}; unexpected ids {unexpected or '[]'}")
    if expected_order is not None:
        checks += 1
        wanted_order = [value.lower() for value in expected_order]
        actual_order = [value.lower() for value in actual_ids if value.lower() in wanted_order]
        if actual_order != wanted_order:
            failures.append(f"id ordering expected {wanted_order}, observed {actual_order}")

    if failures:
        return _axis(False, "; ".join(failures))
    return _axis(True, "not specified" if checks == 0 else f"passed {checks} aggregation check(s)")


def grade_final_answer(expected, answer, error):
    outcome = expected["outcome"]
    failures = []
    if outcome == "failed":
        if not error:
            failures.append("expected a failed turn with an error")
    else:
        if error:
            failures.append(f"unexpected replay error: {error}")
        if not answer.strip():
            failures.append("response is empty")
        if outcome == "asked-appropriately" and "?" not in answer:
            failures.append("expected a clarifying question")
    for required in expected.get("required_strings", []):
        if required not in answer:
            failures.append(f"missing required string {required!r}")
    for forbidden in expected.get("forbidden_strings", []):
        if forbidden in answer:
            failures.append(f"found forbidden string {forbidden!r}")
    if failures:
        return _axis(False, "; ".join(failures))
    return _axis(True, f"response satisfies {outcome} checks")


def grade_fixture(question, observation):
    fixture_id = question.get("fixture_id", "<unknown>")
    expected = question.get("expected")
    validate_expected(fixture_id, expected)
    observed = normalize_replay_entry(fixture_id, observation)
    calls = observed["tool_calls"]
    axes = {
        "intent": grade_intent(expected, observed),
        "tool_selection": grade_tool_selection(expected, calls),
        "argument_accuracy": grade_argument_accuracy(expected.get("tool_calls"), calls),
        "tool_call_sequence": grade_tool_call_sequence(expected, calls),
        "aggregation": grade_aggregation(
            expected, observed["answer"], observed["numbers"], observed["ids"]
        ),
        "final_answer": grade_final_answer(expected, observed["answer"], observed["error"]),
    }
    passed = all(axes[axis]["passed"] for axis in AXES)
    return {
        "expected_outcome": expected["outcome"],
        "outcome": expected["outcome"] if passed else "failed",
        "verdict": "PASS" if passed else "FAIL",
        "axes": axes,
    }


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
    if isinstance(tool_calls, list):
        tool_calls = Counter(call["name"] for call in tool_calls)
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
    if isinstance(tool_calls, list):
        tool_calls = Counter(call["name"] for call in tool_calls)
    return ", ".join(f"{tool}×{count}" for tool, count in sorted(tool_calls.items()))



def _actor_line(actor):
    """One line naming who asked. A score is not comparable without it (#1706)."""
    if actor.get("not_applicable"):
        return f"Actor: n/a — {actor['not_applicable']}"
    if actor.get("error"):
        return f"Actor: **unknown** — {actor['error']}"
    roles = ", ".join(actor.get("effective_roles") or []) or "none"
    version = actor.get("permission_catalog_version")
    suffix = f", permission catalog v{version}" if version is not None else ""
    return f"Actor: `{actor.get('subject')}` (roles: {roles}{suffix})"


def write_markdown(out_dir, record):
    """Emit either the live grading skeleton or a deterministic replay report."""
    provenance = record["questions_file"]
    lines = [
        f"# Analytics gate chat-path run — {record['started_at'][:10]}",
        "",
    ]
    if record.get("void"):
        # Above everything, including the verdict. --allow-role-mismatch otherwise produces a
        # report indistinguishable from a valid one — which is the exact defect #1706 is about,
        # re-created inside the escape hatch added to fix it.
        lines += [
            "> [!CAUTION]",
            f"> **THIS RUN IS VOID — do not quote its score.** {record['void_reason']}.",
            "> It was produced with `--allow-role-mismatch`. A caller without the corpus's"
            " permission codes is offered a different tool set and answers honestly that the"
            " platform cannot do what was asked, so its failures are not model failures (#1706).",
            "",
        ]
    lines += [
        f"Questions: `{provenance['path']}` blob `{provenance['blob_sha']}`"
        f" (repo commit `{provenance['commit']}`"
        + (", **uncommitted edits present**" if provenance["uncommitted"] else "")
        + ")",
        "Ground truth: `pos-mcp-server/src/test/resources/eval/analytics-gate/"
        "ground-truth/EXPECTED.md`",
        _actor_line(record.get("actor") or {}),
    ]
    replay = record.get("mode") == "replay"
    if replay:
        lines.extend([
            f"Replay report: `{record['replay_report']}` - questions graded:"
            f" {len(record['results'])}",
            (
                f"Window grading: {record['summary'].get('window_counts')} (source: {record.get('trace_source')})"
            ),
            (
                f"Overall verdict: **{record['summary']['verdict']}**"
                + (" — but see the VOID banner above; this verdict is not usable" if record.get("void") else "")
            ),
            "",
            "| Q | Outcome | Verdict | Failed axes | Elapsed | Tool calls |",
            "|---|---|---|---|---|---|",
        ])
    else:
        lines.extend([
            f"Endpoint: `{record['endpoint']}` \u00b7 questions asked:"
            f" {len(record['results'])}",
            "",
            "Verdicts are graded by hand against the ground truth (plan \u00a72.1 criterion 1);"
            " this file is generated with the Verdict column empty. Tool calls are not observable"
            " on this endpoint today (#1676 \u2014 see script docstring),"
            " so that column always reads n/a and a question"
            " with an expected_plan gets a plan_check note instead of an automatic verdict.",
            "",
            "| Q | Verdict | Window the question fixes | Elapsed | Tool calls |",
            "|---|---|---|---|---|",
        ])
    for result in record["results"]:
        if replay:
            failed_axes = [name for name, axis in result["axes"].items() if not axis["passed"]]
            lines.append(
                f"| {result['fixture_id']} | {result['outcome']} | {result['verdict']} |"
                f" {', '.join(failed_axes) or '(none)'} | {result['elapsed_s']}s |"
                f" {format_tool_calls(result['tool_calls'])} |"
            )
        else:
            window = result["window"]
            check = result.get("window_check") or {}
            lines.append(
                f"| {result['fixture_id']} |  | {window['shape']}, {window['resolved_range']}"
                f" — window: **{check.get('verdict', 'UNGRADED')}** ({check.get('detail', '')}) |"
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
        if replay:
            lines.extend([
                f"**Outcome:** {result['outcome']} - **Verdict:** {result['verdict']}",
                "",
                "| Axis | Result | Details |",
                "|---|---|---|",
            ])
            for name in AXES:
                axis = result["axes"][name]
                details = axis["details"].replace("|", "\\|").replace("\n", " ")
                lines.append(f"| {name} | {'PASS' if axis['passed'] else 'FAIL'} | {details} |")
            lines.extend(["", "**Tool calls:**"])
            if result["tool_calls"]:
                for call in result["tool_calls"]:
                    arguments = json.dumps(
                        call["arguments"], sort_keys=True, separators=(",", ":")
                    )
                    lines.append(f"{call['sequence']}. `{call['name']}` `{arguments}`")
            else:
                lines.append("(none)")
            lines.append("")
        lines.append("```")
        lines.append(result["error"] or result["answer"] or "(empty response)")
        lines.append("```")
        lines.append("")
    (out_dir / "run.md").write_text("\n".join(lines), encoding="utf-8")



def render_alternate_markdown(record):
    """Renders the band/sequence record as the run.md the --out contract promises.

    The sequences table deliberately shows must_reference / fails_if instead of a verdict column:
    this mode is UNSCORED by design, and a report that looked scored would invite the reader to
    trust a judgement no code here made.
    """
    lines = [
        f"# analytics gate — {record['mode']} mode",
        "",
        f"- started: {record['started_at']}",
        f"- endpoint: {record['endpoint']}",
        f"- corpus: {record['corpus']}",
        f"- actor: {record['actor'].get('subject', record['actor'].get('error', 'unknown'))}",
        f"- verdict: **{record['summary']['verdict']}**",
        "",
    ]

    if record["mode"] == "bands":
        lines += [
            f"{record['summary']['passed']} passed, {record['summary']['failed']} failed; "
            f"observed outcomes {record['summary']['by_outcome']}",
            "",
            "| fixture | band | expected | observed | verdict |",
            "| --- | --- | --- | --- | --- |",
        ]
        for result in record["results"]:
            lines.append(
                f"| {result['fixture_id']} | {result['band']} | {result['expected_outcome']} "
                f"| {result['observed_outcome']} | {result['verdict']} |"
            )
    else:
        lines += [
            f"{record['summary']['sequences']} sequences, {record['summary']['turns']} turns. "
            f"{record['summary']['note']}",
            "",
            "| sequence | turn | utterance | must_reference | fails_if |",
            "| --- | --- | --- | --- | --- |",
        ]
        for sequence in record["sequences"]:
            for index, turn in enumerate(sequence["turns"], start=1):
                lines.append(
                    f"| {sequence['sequence_id']} | {index} | {turn['utterance']} "
                    f"| {turn.get('must_reference', '')} | {turn.get('fails_if', '')} |"
                )

    return "\n".join(lines) + "\n"


def run_alternate_mode(args, token):
    """Executes the behaviour-band or multi-turn corpus and writes its own record.

    Kept separate from the question path rather than folded into it: these score an OUTCOME and a
    conversation, not a number against ground truth, and forcing them through a grader built for
    single-turn numeric answers would misreport both.
    """
    repo_root = Path(__file__).resolve().parents[1]
    corpus = repo_root / (BANDS_PATH if args.mode == "bands" else SEQUENCES_PATH)
    document = json.loads(corpus.read_text(encoding="utf-8"))
    api_version = os.environ.get("MCP_API_VERSION", "1")

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    record = {
        "started_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "endpoint": args.url,
        "mode": args.mode,
        "corpus": str(corpus.relative_to(repo_root)),
        "actor": actor_provenance(token) if token else {"error": "no token"},
    }

    if args.mode == "bands":
        record["results"] = run_bands(args.url, token, api_version, document, args.timeout)
        counts = Counter(result["verdict"] for result in record["results"])
        record["summary"] = {
            "verdict": "PASS" if counts.get("FAIL", 0) == 0 else "FAIL",
            "passed": counts.get("PASS", 0),
            "failed": counts.get("FAIL", 0),
            "by_outcome": dict(sorted(Counter(r["observed_outcome"] for r in record["results"]).items())),
        }
    else:
        record["sequences"] = run_sequences(args.url, token, api_version, document, args.timeout)
        # Deliberately no automatic verdict: whether a follow-up carried its context is a judgement,
        # and inventing a machine verdict here would be a number nobody should trust.
        record["summary"] = {
            "verdict": "UNSCORED",
            "sequences": len(record["sequences"]),
            "turns": sum(len(s["turns"]) for s in record["sequences"]),
            "note": "each turn carries must_reference / fails_if for a grader to apply",
        }

    (out_dir / "run.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    (out_dir / "run.md").write_text(render_alternate_markdown(record), encoding="utf-8")
    print(f"\nwrote {out_dir / 'run.json'} and {out_dir / 'run.md'}  summary={record['summary']}")
    return None


def server_build(chat_url, token):
    """The build the server is running, so a run says what it measured.

    A gate number is only comparable against another if both name the build they ran against. On
    2026-09-05 a deploy landed mid-run and the results were unattributable after the fact — the file
    recorded the questions blob and the actor, and nothing about the service. Returns a reason
    string rather than raising: not knowing the build must not stop a run.
    """
    base = chat_url.split("/v1/")[0]
    request = urllib.request.Request(f"{base}/actuator/info")
    request.add_header("Authorization", f"Bearer {token}")
    request.add_header("Accept", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            info = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, TimeoutError, json.JSONDecodeError) as exc:
        return {"error": f"{type(exc).__name__}: {exc}"}
    build = info.get("build") if isinstance(info, dict) else None
    if isinstance(build, dict):
        return {k: build.get(k) for k in ("name", "version", "time") if build.get(k)}
    return {"error": "no build section in actuator/info"}


def build_parser():
    """The CLI parser, extracted so tests can assert real defaults.

    It lived inside main(), so a test wanting to check a default had to hand-build a
    Namespace — which pins whatever the test author typed, not what the CLI does. That is
    how test_off_by_default kept passing after the default flipped.
    """
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", required=True, help="directory for run.json / run.md")
    parser.add_argument("--questions", default=str(QUESTIONS_PATH))
    parser.add_argument("--url", default=os.environ.get("MCP_CHAT_URL", DEFAULT_URL))
    parser.add_argument("--token", default=None, help="bearer token; prefer MCP_BEARER_TOKEN")
    parser.add_argument("--env-file", default=None, help="KEY=VALUE file to read config from")
    parser.add_argument(
        "--traces-url",
        default=os.environ.get("MCP_TRACES_URL"),
        help="turn-trace endpoint; defaults to the chat URL's host with /v1/eval/turn-traces",
    )
    parser.add_argument(
        "--mode",
        choices=["questions", "bands", "sequences"],
        default="questions",
        help="questions (default), bands (#1689 outcome bands), or sequences (#1690 multi-turn)",
    )
    parser.add_argument(
        "--isolate-turns",
        dest="isolate_turns",
        action="store_true",
        default=True,
        help="send a distinct conversationId per question so each is an independent single turn "
        "(#1735). On by default; the server has accepted the field since #1757.",
    )
    parser.add_argument(
        "--no-isolate-turns",
        dest="isolate_turns",
        action="store_false",
        help="run the whole corpus on one shared conversation, the pre-#1757 behaviour. For "
        "reproducing a historical run or measuring the sharing effect — not a fallback.",
    )
    parser.add_argument(
        "--run-id",
        default=None,
        help="prefix for per-question conversation ids; defaults to a timestamp",
    )
    parser.add_argument(
        "--expect-role",
        default=None,
        help=f"abort unless the token carries this role (default {EXPECTED_ROLE_DEFAULT}); "
        "pass an empty string to skip the check",
    )
    parser.add_argument(
        "--allow-role-mismatch",
        action="store_true",
        help="run anyway when the actor's role is not --expect-role, and mark the record void",
    )
    parser.add_argument(
        "--replay-report",
        default=None,
        help="machine-readable JSON report; disables authentication and HTTP calls",
    )
    parser.add_argument("--only", default=None, help="comma-separated fixture ids, e.g. q09,q12")
    parser.add_argument("--all", action="store_true", help="also ask the excluded questions")
    parser.add_argument("--timeout", type=int, default=180, help="per-turn timeout, seconds")
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.env_file:
        load_env_file(args.env_file)
    # Resolved after load_env_file, not as an argparse default: a default is evaluated at
    # add_argument time, so MCP_EXPECTED_ROLE set in an --env-file was silently ignored while
    # MCP_BEARER_TOKEN from the same file worked.
    if args.expect_role is None:
        args.expect_role = os.environ.get("MCP_EXPECTED_ROLE", EXPECTED_ROLE_DEFAULT)
    if args.run_id is None:
        args.run_id = datetime.now(timezone.utc).strftime("gate-%Y%m%dT%H%M%SZ")

    token = args.token or os.environ.get("MCP_BEARER_TOKEN")
    if not args.replay_report and not token:
        sys.exit("no bearer token: pass --token or set MCP_BEARER_TOKEN (or --env-file)")

    # Replay grades a recorded trace and issues no request, so the local token says nothing about
    # who produced it. Recording this run's actor there would be a FALSE provenance claim — worse
    # than the missing one #1706 is about — and refusing the run over a role it never uses would
    # block a check that needs no credentials at all.
    replaying = bool(args.replay_report)
    actor = (
        {"not_applicable": "replay mode grades a recorded trace; this run issued no request"}
        if replaying
        else actor_provenance(token) if token else {"error": "no token"}
    )
    role_mismatch = None
    if not replaying and token and args.expect_role:
        effective = actor.get("effective_roles") or []
        if actor.get("error"):
            # Distinct from a role mismatch: the roles list is empty because nothing could be
            # read, not because the actor holds none. Saying "carries roles []" here would send
            # the reader to check permissions on an actor whose token never parsed.
            role_mismatch = f"could not establish the actor: {actor['error']}"
        elif args.expect_role not in effective:
            role_mismatch = (
                f"actor {actor.get('subject')!r} carries roles {effective} "
                f"but this gate expects {args.expect_role!r}"
            )
        if role_mismatch and not args.allow_role_mismatch:
            why = (
                "The token's claims could not be read, so there is no way to tell whether this "
                "actor can reach the tools the corpus needs."
                if actor.get("error")
                else "A caller without the corpus's permission codes is offered a different tool set "
                "and answers honestly that the platform cannot do what was asked — twelve "
                "well-formed deflections that look like model failures and are not."
            )
            sys.exit(
                f"refusing to run: {role_mismatch}.\n{why} (#1706)\n"
                "Use the ITEST_USERNAME/ITEST_PASSWORD pair from the itest credentials file, or pass "
                "--expect-role '' to skip this check, or --allow-role-mismatch to run anyway."
            )

    if args.mode in {"bands", "sequences"}:
        return run_alternate_mode(args, token)

    questions_path = Path(args.questions).resolve()
    document = json.loads(questions_path.read_text(encoding="utf-8"))
    selected = select(document["questions"], args.only, args.all)
    if not selected:
        sys.exit("no questions selected")
    replay_by_fixture = None
    if args.replay_report:
        try:
            replay_by_fixture = load_replay_report(
                args.replay_report, {question["fixture_id"] for question in document["questions"]}
            )
        except ValueError as exc:
            parser.error(str(exc))
        missing = [
            question["fixture_id"] for question in selected
            if question["fixture_id"] not in replay_by_fixture
        ]
        if missing:
            parser.error(
                f"replay report is missing selected fixture_id(s): {', '.join(sorted(missing))}"
            )

    # Provenance first: a run that cannot be traced back to its questions is refused, and
    # refusing before the output directory exists leaves nothing half-written behind.
    provenance = questions_provenance(questions_path)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    # The date point-in-time questions are graded against is THIS RUN'S date, not the corpus's
    # eval_as_of. Anchoring to eval_as_of would reinstate the exact defect #1709 is about: the
    # assistant resolves from its own clock, so a corpus date of 2026-09-01 makes q05/q13 fail on
    # every day but one. eval_as_of stays in the record as documentation of when the ground-truth
    # figures were computed; it no longer constrains grading.
    run_as_of = datetime.now(timezone.utc).date()
    run_started_at = datetime.now(timezone.utc) - timedelta(minutes=5)

    record = {
        "started_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "endpoint": args.url,
        "eval_as_of": document.get("eval_as_of"),
        "graded_as_of": run_as_of.isoformat(),
        "questions_file": provenance,
        "actor": actor,
        "server_build": server_build(args.url, token),
        "graded_from_traces": True,
        "void": bool(role_mismatch),
        "results": [],
    }
    if role_mismatch:
        # Recorded, not just warned: a score produced by the wrong actor must be self-evidently
        # void when someone reads the file later, not only in the terminal of whoever ran it.
        record["void_reason"] = role_mismatch
    if replay_by_fixture is not None:
        record["mode"] = "replay"
        record["replay_report"] = str(Path(args.replay_report).resolve())
    else:
        record["mode"] = "live"
    if record["questions_file"]["uncommitted"]:
        print(f"WARNING: {record['questions_file']['path']} has uncommitted edits;"
              " this run is not reproducible from a commit", file=sys.stderr)

    api_version = os.environ.get("MCP_API_VERSION", "1")
    for question in selected:
        print(f"{question['fixture_id']} …", flush=True)
        grading = None
        if replay_by_fixture is not None:
            observed = replay_by_fixture[question["fixture_id"]]
            outcome = {
                "answer": observed["answer"],
                "error": observed["error"],
                "elapsed_s": observed["elapsed_s"],
            }
            tool_calls = observed["tool_calls"]
            try:
                grading = grade_fixture(question, observed)
            except ValueError as exc:
                parser.error(str(exc))
        else:
            outcome = ask(
                args.url,
                token,
                api_version,
                question["utterance"],
                args.timeout,
                conversation_id=turn_conversation_id(args, question["fixture_id"]),
            )
            tool_calls = None
        expected_plan = question.get("expected_plan")
        plan_check = check_expected_plan(expected_plan, tool_calls)
        result = {
            "fixture_id": question["fixture_id"],
            "expected_section": question["expected_section"],
            "ground_truth_sql": question["ground_truth_sql"],
            "utterance": question["utterance"],
            "window": question["window"],
            "window_check": None,  # filled below, once the answer exists
            "expected_plan": expected_plan,
            "tool_calls": tool_calls,
            "plan_check": plan_check,
            **outcome,
        }
        if grading is not None:
            result.update(grading)
        result["window_check"] = None  # graded after the run, once traces can be fetched
        record["results"].append(result)
        print(f"  {outcome['elapsed_s']}s"
              + (f" — {outcome['error']}" if outcome["error"] else "")
              + (f" — {plan_check}" if plan_check and plan_check.startswith("FAIL") else ""),
              flush=True)

    # Grade windows AFTER the loop: traces are written as each turn completes, so one fetch at the
    # end covers the whole run instead of a request per question (#1743).
    resolved_by_utterance = {}
    trace_note = "replay mode: no live traces"
    if replay_by_fixture is None and token:
        traces, failure = fetch_traces(args.traces_url or traces_url_for(args.url), token, run_started_at)
        if failure:
            trace_note = f"falling back to answer parsing — {failure}"
            # Recorded as its own fact rather than folded into `void`, which means "the actor was
            # wrong, this score is invalid" — a different claim. Answer parsing grades whatever
            # prose the model happened to quote, so a corpus expecting trace-level evidence
            # degrades to mostly UNGRADED, and UNGRADED does not fail the summary. On 2026-09-05 a
            # deploy landed mid-run, the trace endpoint answered 503, eleven of twelve questions
            # came back UNGRADED, and the output was indistinguishable from a genuine collapse in
            # capability until the container logs were read. A reader needs to see that the run
            # could not measure what it claims to measure.
            record["graded_from_traces"] = False
        else:
            for trace in traces:
                message = trace.get("userMessage")
                if message:
                    # setdefault with the possibly-EMPTY list: a trace that resolved no window is
                    # evidence that none was resolved, which must not be mistaken for "no trace".
                    resolved_by_utterance.setdefault(message, window_from_trace(trace))
            trace_note = f"{len(traces)} trace(s) fetched, {len(resolved_by_utterance)} with a resolved window"
    record["trace_source"] = trace_note

    for result in record["results"]:
        resolved = resolved_by_utterance.get(result["utterance"])
        verdict, detail = grade_window(
            {"fixture_id": result["fixture_id"], "window": result["window"]},
            result.get("answer"),
            run_as_of,
            resolved,
        )
        result["window_check"] = {"verdict": verdict, "detail": detail}

    if replay_by_fixture is not None:
        outcome_counts = Counter(result["outcome"] for result in record["results"])
        # A window FAIL must be able to fail the run. Recording it in a JSON field a reader has to go
        # looking for is the same "clean-looking report" failure #1706 was about, one axis down.
        window_counts = Counter(
            (result.get("window_check") or {}).get("verdict", "UNGRADED") for result in record["results"]
        )
        failed = sum(result["verdict"] == "FAIL" for result in record["results"])
        window_failed = window_counts.get("FAIL", 0)
        record["summary"] = {
            # A window FAIL fails the run. The window is not a side note: answering the right
            # question on the wrong six months is a wrong answer, and a verdict that ignored it
            # would report PASS for exactly the q09/q12/q15 failures this work exists to catch.
            "verdict": "PASS" if failed == 0 and window_failed == 0 else "FAIL",
            "passed": len(record["results"]) - failed,
            "failed": failed,
            "outcomes": dict(sorted(outcome_counts.items())),
            "window_counts": dict(sorted(window_counts.items())),
        }

    (out_dir / "run.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    write_markdown(out_dir, record)
    print(f"\nwrote {out_dir / 'run.json'} and {out_dir / 'run.md'}")
    print(f"questions blob sha: {record['questions_file']['blob_sha']}")


if __name__ == "__main__":
    main()
