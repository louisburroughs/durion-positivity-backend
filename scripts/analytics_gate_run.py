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
import urllib.request
from datetime import datetime, timezone
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
    bits = claims.get("permissionCodes") or claims.get("perm_bits") or claims.get("permissions")
    return {
        "subject": claims.get("sub") or claims.get("username"),
        "roles": roles,
        "effective_roles": effective,
        "permission_bits_length": len(bits) if isinstance(bits, str) else None,
        "permission_count": len(bits) if isinstance(bits, (list, dict)) else None,
    }


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


def write_markdown(out_dir, record):
    """Emit either the live grading skeleton or a deterministic replay report."""
    provenance = record["questions_file"]
    lines = [
        f"# Analytics gate chat-path run — {record['started_at'][:10]}",
        "",
        f"Questions: `{provenance['path']}` blob `{provenance['blob_sha']}`"
        f" (repo commit `{provenance['commit']}`"
        + (", **uncommitted edits present**" if provenance["uncommitted"] else "")
        + ")",
        "Ground truth: `pos-mcp-server/src/test/resources/eval/analytics-gate/"
        "ground-truth/EXPECTED.md`",
    ]
    replay = record.get("mode") == "replay"
    if replay:
        lines.extend([
            f"Replay report: `{record['replay_report']}` - questions graded:"
            f" {len(record['results'])}",
            f"Overall verdict: **{record['summary']['verdict']}**",
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


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", required=True, help="directory for run.json / run.md")
    parser.add_argument("--questions", default=str(QUESTIONS_PATH))
    parser.add_argument("--url", default=os.environ.get("MCP_CHAT_URL", DEFAULT_URL))
    parser.add_argument("--token", default=None, help="bearer token; prefer MCP_BEARER_TOKEN")
    parser.add_argument("--env-file", default=None, help="KEY=VALUE file to read config from")
    parser.add_argument(
        "--expect-role",
        default=os.environ.get("MCP_EXPECTED_ROLE", EXPECTED_ROLE_DEFAULT),
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
    args = parser.parse_args(argv)

    if args.env_file:
        load_env_file(args.env_file)
    token = args.token or os.environ.get("MCP_BEARER_TOKEN")
    if not args.replay_report and not token:
        sys.exit("no bearer token: pass --token or set MCP_BEARER_TOKEN (or --env-file)")

    actor = actor_provenance(token) if token else {"error": "no token (replay mode)"}
    role_mismatch = None
    if token and args.expect_role:
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

    record = {
        "started_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "endpoint": args.url,
        "eval_as_of": document.get("eval_as_of"),
        "questions_file": provenance,
        "actor": actor,
        "results": [],
    }
    if role_mismatch:
        # Recorded, not just warned: a score produced by the wrong actor must be self-evidently
        # void when someone reads the file later, not only in the terminal of whoever ran it.
        record["void"] = True
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
            outcome = ask(args.url, token, api_version, question["utterance"], args.timeout)
            tool_calls = None
        expected_plan = question.get("expected_plan")
        plan_check = check_expected_plan(expected_plan, tool_calls)
        result = {
            "fixture_id": question["fixture_id"],
            "expected_section": question["expected_section"],
            "ground_truth_sql": question["ground_truth_sql"],
            "utterance": question["utterance"],
            "window": question["window"],
            "expected_plan": expected_plan,
            "tool_calls": tool_calls,
            "plan_check": plan_check,
            **outcome,
        }
        if grading is not None:
            result.update(grading)
        record["results"].append(result)
        print(f"  {outcome['elapsed_s']}s"
              + (f" — {outcome['error']}" if outcome["error"] else "")
              + (f" — {plan_check}" if plan_check and plan_check.startswith("FAIL") else ""),
              flush=True)

    if replay_by_fixture is not None:
        outcome_counts = Counter(result["outcome"] for result in record["results"])
        failed = sum(result["verdict"] == "FAIL" for result in record["results"])
        record["summary"] = {
            "verdict": "PASS" if failed == 0 else "FAIL",
            "passed": len(record["results"]) - failed,
            "failed": failed,
            "outcomes": dict(sorted(outcome_counts.items())),
        }

    (out_dir / "run.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    write_markdown(out_dir, record)
    print(f"\nwrote {out_dir / 'run.json'} and {out_dir / 'run.md'}")
    print(f"questions blob sha: {record['questions_file']['blob_sha']}")


if __name__ == "__main__":
    main()
