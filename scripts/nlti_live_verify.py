#!/usr/bin/env python3
"""NLTI live HTTP verification harness (#1367) — exercises the real pos-mcp-server HTTP surface
through pos-api-gateway as N configured personas, harvests the `nlti.request.telemetry` stream from
Loki, and emits both a machine-readable JSON result file and a paste-ready markdown evidence block
shaped like the gate blocks in `pos-mcp-server/docs/implementation_checklist.md`.

Why this exists: `scripts/eval_live.py` drives Postgres/pgvector and the embedding model directly and
never touches the HTTP surface. Six of the eight open gate issues (#1213 #1214 #1215 #1216 #1218
#1219) need exactly that surface exercised end-to-end, with permission scoping applied for real.
Wave 0.3 of `pos-mcp-server/docs/gate-closeout-plan-1212-1219.md`.

Suites (each is an independent verification routine; `--suite all` runs every one):

    equivalence  Gate 2A / #1214 — same request via POST /v1/mcp/chat and POST /v1/mcp/chat/stream
                 must select the same tools, compose the same prompt layers, resolve the same
                 workflow state / persona / RAG docs; fills the latency metrics.
    persona      Gate 1  / #1213 — per-persona prompt-layer telemetry emission + answer capture.
    workflow     Gate 2C / #1215 — POST /v1/nlt/sessions/{id}/workflow-state, then assert the
                 non-IDLE gated tool set actually activates and shows up in telemetry.
    router       Gate 4  / #1216 — routing mix / tier / fallback / latency / quality for the
                 tiered model router.
    write-gate   Gate 6  / #1218 — write-confirmation full flow + fail-closed behaviour.
    admin        Gate 7  / #1219 — admin flows, permission fail-closed, shadow->live tuning.

Safety model (non-negotiable):

  * `--dry-run` (offline) prints the exact request plan and exits 0 without opening a socket.
  * Default live mode is `observe`: it never mutates alpha *business* data. Chat turns, NLTI prompt
    submissions (which only ever *preview* a write plan), session workflow-state changes, and plan
    cancellation touch only the caller's own MCP session state and run by default. Every request
    that can change data outside the caller's session — `POST /v1/nlt/requests/{id}/confirm` and the
    admin CRUD writes — is classified BUSINESS_WRITE / ADMIN_WRITE and is SKIPPED unless
    `--allow-writes` is passed.
  * `--read-only` is stricter still: only GET / auth requests execute; everything else is skipped.
  * The #1218 write target is an OPEN DECISION (plan decision C) and is deliberately NOT defaulted.
    `--suite write-gate` with `--allow-writes` requires `--write-target <file.json>`; without it the
    suite runs its read-safe preview/fail-closed half and reports the confirm checks as SKIPPED.

Telemetry harvest (plan decision H): Loki/LogQL only — never `docker compose logs`. The emitter is
`internal/telemetry/LoggingNltiTelemetryEmitter`, which writes one JSON line per request to the
dedicated `nlti.telemetry` logger; promtail (`observability/promtail-config.yml`) ships it with the
`service` label set from the compose service name. Every request — the chat endpoints included —
is sent with a generated `X-Correlation-Id` and records are joined primarily by correlationId (the
servlet correlation filter echoes the header on all endpoints). Against a server that predates that
filter the chat records still carry a random correlationId; the harness then falls back to the
request time-window + actor-role join and downgrades every check that depended on it to
UNVERIFIED-ATTRIBUTION, because the 2026-08-19 live run proved the window join can attribute the
PREVIOUS persona's record (role/permCount shifted one position across all four personas).

No third-party dependencies: stdlib only (urllib/json/argparse), matching `scripts/eval_live.py` and
`scripts/gap_harness/api.py`. YAML persona files are supported only when PyYAML happens to be
installed; JSON always works.

Exit codes: 0 = every executed check passed (or `--dry-run`), 1 = at least one check FAILED,
2 = configuration / usage error, 3 = infrastructure error (auth, gateway, or Loki unreachable).
UNVERIFIED-ATTRIBUTION checks do not flip the exit code but hold the gate decision (HOLD) in the
markdown — they are never countable as evidence.

Usage:
    # offline review of the exact request plan (Wave 0 — no alpha access needed)
    python3 scripts/nlti_live_verify.py --suite all --dry-run \\
        --personas scripts/fixtures/nlti-personas.example.json

    # Wave 1: closes #1213 + #1214
    NLTI_PERSONA_OPS_ADMIN_PASSWORD=... NLTI_PERSONA_TECHNICIAN_PASSWORD=... \\
        python3 scripts/nlti_live_verify.py --suite equivalence,persona \\
        --gateway-url http://localhost:8080 --loki-url http://localhost:3100 \\
        --personas /opt/durion/alpha/nlti-personas.json

    # Wave 1: closes #1215 (after V34 seeds PROCESSING_RETURN)
    python3 scripts/nlti_live_verify.py --suite workflow --workflow-state PROCESSING_RETURN ...

    # Wave 1: #1218 — needs the open decision C target, and an explicit write opt-in
    python3 scripts/nlti_live_verify.py --suite write-gate --allow-writes \\
        --write-target /opt/durion/alpha/nlti-write-target.json ...
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = ROOT / "pos-mcp-server/target/nlti-live-verify"
DEFAULT_PERSONAS = ROOT / "scripts/fixtures/nlti-personas.example.json"

EXIT_OK = 0
EXIT_CHECK_FAILED = 1
EXIT_CONFIG = 2
EXIT_INFRA = 3

# Request impact classes. Only READ runs under --read-only; BUSINESS_WRITE/ADMIN_WRITE need
# --allow-writes. SESSION covers calls that mutate nothing but the caller's own MCP session.
READ = "READ"
SESSION = "SESSION"
BUSINESS_WRITE = "BUSINESS_WRITE"
ADMIN_WRITE = "ADMIN_WRITE"

PASS = "PASS"
FAIL = "FAIL"
SKIP = "SKIP"
# A check whose telemetry evidence had to be joined by the legacy time-window+role fallback
# (server predating the X-Correlation-Id servlet filter). Deliberately NOT PASS/FAIL: it renders
# unchecked in markdown, holds the gate decision, and is counted separately, so a fallback-joined
# check can never be read as solid evidence (2026-08-19 live-run defect 1).
UNVERIFIED = "UNVERIFIED-ATTRIBUTION"

# suite -> (gate id, issue, human title)
SUITES = {
    "equivalence": ("2A", "#1214", "Live blocking-vs-streaming equivalence"),
    "persona": ("1", "#1213", "Prompt-layer telemetry + live answer quality"),
    "workflow": ("2C", "#1215", "Non-IDLE workflow activation"),
    "router": ("4", "#1216", "Tiered router routing-mix, latency and quality"),
    "write-gate": ("6", "#1218", "Live write-gate full-flow verification"),
    "admin": ("7", "#1219", "Admin flows, permissions and shadow->live tuning"),
}

WORKFLOW_STATES = ("IDLE", "CREATING_PO", "RECEIVING_ASN", "INVENTORY_RECON", "PROCESSING_RETURN")

# NltiRequestTelemetry.PromptLayer — every layered prompt must at minimum carry BASE + ROLE (Gate 1).
REQUIRED_PROMPT_LAYERS = ("BASE", "ROLE")
# NltiRequestTelemetry.Tier
TIER_SIMPLE = "T2_SIMPLE"
TIER_COMPLEX = "T2_COMPLEX"
TIER_RULE = "T0_RULE"

TELEMETRY_EVENT_TYPE = "nlti.request.telemetry"
# Gate 2C dedicated transition event (internal/telemetry/NltiWorkflowTransitionTelemetry),
# emitted on the same `nlti.telemetry` logger and keyed by the caller's X-Correlation-Id.
TRANSITION_EVENT_TYPE = "nlti.workflow.transition"

# 2026-08-19 live-run defect 3: IntentParserServiceImpl:40 classifies ACTION only when risk is
# HIGH, and HIGH means the prompt contains "delete"/"remove" or starts with "bulk ". Create/update
# phrasings are UNKNOWN -> no write plan -> planId=None, so the write-gate ACTION probe must use a
# delete-verb phrasing to reach the gate at all. --write-gate-action-prompt overrides this default;
# a --write-target prompt takes precedence over both.
DEFAULT_WRITE_GATE_ACTION_PROMPT = "Delete the draft purchase order PO-NLTI-PROBE-001"
# wg-intent-gap ALWAYS submits this create-style prompt to document, on every run, that
# create/update phrasings cannot reach the write gate (#1218 product gap; see run_write_gate).
WRITE_GATE_INTENT_GAP_PROMPT = "Create a purchase order for 10 oil filters"

# Default probe prompts. Deliberately read-only phrasings for everything except the router's write
# class (which must still classify as an ACTION without any confirmation being issued). Override the
# whole set with --queries <file.json>.
DEFAULT_QUERIES = {
    "equivalence": [
        {"id": "eq-open-workorders", "prompt": "Show me the open workorders for today"},
        {"id": "eq-part-stock", "prompt": "How many oil filters do we have on hand?"},
        {"id": "eq-policy", "prompt": "What does the receiving policy say about short shipments?"},
    ],
    "persona": [
        {"id": "persona-default", "prompt": "What can you help me with in my role?"},
    ],
    "workflow": [
        {"id": "wf-probe", "prompt": "What should I do next in this workflow?"},
    ],
    "router": [
        {"id": "rt-simple-greeting", "prompt": "hello", "class": "simple", "expect_tier": TIER_RULE},
        {"id": "rt-simple-thanks", "prompt": "thanks", "class": "simple", "expect_tier": TIER_RULE},
        {"id": "rt-simple-lookup", "prompt": "What is a VIN?", "class": "simple",
         "expect_tier": TIER_SIMPLE},
        {"id": "rt-simple-stock", "prompt": "How many brake pads are in stock?", "class": "simple",
         "expect_tier": TIER_SIMPLE},
        {"id": "rt-complex-accounting", "prompt": "Explain how the journal entry for a customer "
                                                  "refund is posted across accounts",
         "class": "sensitive", "expect_tier": TIER_COMPLEX},
        {"id": "rt-complex-tax", "prompt": "How is sales tax computed for a multi-jurisdiction "
                                           "invoice?", "class": "sensitive",
         "expect_tier": TIER_COMPLEX},
        {"id": "rt-write-po", "prompt": "Create a purchase order for 10 oil filters",
         "class": "write", "expect_tier": TIER_COMPLEX},
        {"id": "rt-write-adjust", "prompt": "Adjust the on-hand quantity of part OF-1234 to 12",
         "class": "write", "expect_tier": TIER_COMPLEX},
    ],
    "write-gate": [
        {"id": "wg-action", "prompt": DEFAULT_WRITE_GATE_ACTION_PROMPT},
    ],
}

REFUSAL_MARKERS = (
    "i don't have permission",
    "i do not have permission",
    "you don't have permission",
    "not authorized",
    "i'm not able to",
    "i am not able to",
    "i cannot help with",
)

# Persona permission probes (2026-08-19 live-run defect 2: expectsPermissions/lacksPermissions were
# parsed but never asserted). NltiRequestTelemetry.Actor carries only (primaryRole,
# permissionCodeCount) — the permission-code list is NOT emitted — so every declared code is
# verified by probing the endpoint class it gates: @PreAuthorize rejects before the handler, so any
# non-401/403 response proves the authority is held and a 403 proves it is absent.
# Entries: permission -> (method, path, impact, body, stream, note). "{tool}" is substituted with
# --admin-tool-name. mcp:tool:manage uses a no-op revoke of a nonexistent permission code
# (ToolPermissionController#revoke is idempotent and changes nothing for a code that is not
# granted), so the probe cannot mutate even when the persona unexpectedly holds the permission.
PROBE_NONEXISTENT_CODE = "nlti:probe:nonexistent"
PERMISSION_PROBES = {
    "mcp:chat:execute": ("POST", "mcp/chat", SESSION, {"message": "hello"}, False,
                         "McpChatController#chat"),
    "mcp:chat:stream": ("POST", "mcp/chat/stream", SESSION, {"message": "hello"}, True,
                        "McpStreamingChatController#streamChat"),
    "nlti:request:submit": ("POST", "nlt/requests", SESSION,
                            {"prompt": "What is the status of this session?"}, False,
                            "NltiController#submitRequest — previews only, never executes"),
    "nlti:audit:read": ("GET", "nlt/audit", READ, None, False, "AuditController#queryAudit"),
    "mcp:system_prompt:view": ("GET", "prompts", READ, None, False,
                               "SystemPromptController#list"),
    "mcp:llm_api:view": ("GET", "llm-apis", READ, None, False, "LlmApiConfigController#list"),
    "mcp:tool:view": ("GET", "tools/{tool}/permissions", READ, None, False,
                      "ToolPermissionController#list"),
    "mcp:tool:manage": ("DELETE",
                        "tools/{tool}/permissions?permissionCode=" + PROBE_NONEXISTENT_CODE,
                        SESSION, None, False,
                        "ToolPermissionController#revoke — no-op probe (nonexistent code, "
                        "idempotent revoke cannot mutate)"),
}


# --- small helpers -------------------------------------------------------------------------------
def now_utc():
    return datetime.now(timezone.utc)


def iso(ts):
    return ts.strftime("%Y-%m-%dT%H:%M:%SZ")


def env_file(key, env_path=None):
    """Read KEY=value from an env file, mirroring eval_live.env_file (ENV_FILE overrides)."""
    f = Path(env_path or os.environ.get("ENV_FILE", ROOT / ".env"))
    if not f.is_file():
        return None
    for line in f.read_text().splitlines():
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip().strip('"')
    return None


def env_key(name, suffix):
    """NLTI_PERSONA_<SANITISED NAME>_<SUFFIX> — the default secret env var for a persona."""
    slug = re.sub(r"[^A-Za-z0-9]+", "_", name).strip("_").upper()
    return f"NLTI_PERSONA_{slug}_{suffix}"


def percentile(values, pct):
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return float(ordered[0])
    rank = (len(ordered) - 1) * (pct / 100.0)
    low = int(rank)
    high = min(low + 1, len(ordered) - 1)
    return float(ordered[low] + (ordered[high] - ordered[low]) * (rank - low))


def redact(headers):
    """Never print a bearer token or a password into the plan / JSON artefact."""
    out = {}
    for key, value in headers.items():
        if key.lower() in ("authorization", "x-pos-events-secret", "x-scope-orgid"):
            out[key] = "<redacted>"
        else:
            out[key] = value
    return out


def redact_body(body):
    if isinstance(body, dict) and "password" in body:
        clone = dict(body)
        clone["password"] = "<redacted>"
        return clone
    return body


def extract_json_objects(line):
    """Yield every balanced JSON object embedded in a log line.

    The telemetry line is `<logback prefix> nlti.telemetry : {"schemaVersion":1,...}`, so the JSON
    has to be carved out of the formatted line rather than parsed whole.
    """
    depth = 0
    start = -1
    in_string = False
    escaped = False
    for index, char in enumerate(line):
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "{":
            if depth == 0:
                start = index
            depth += 1
        elif char == "}":
            if depth:
                depth -= 1
                if depth == 0 and start >= 0:
                    chunk = line[start:index + 1]
                    try:
                        yield json.loads(chunk)
                    except ValueError:
                        pass
                    start = -1


# --- configuration models ------------------------------------------------------------------------
@dataclass
class Persona:
    name: str
    username: str = ""
    password_env: str = ""
    token_env: str = ""
    expected_role: str = ""
    description: str = ""
    expects_permissions: list = field(default_factory=list)
    lacks_permissions: list = field(default_factory=list)
    probe_prompt: str = ""
    admin: bool = False

    @staticmethod
    def from_dict(raw):
        name = (raw.get("name") or "").strip()
        if not name:
            raise SystemExit("persona entry is missing a 'name'")
        return Persona(
            name=name,
            username=raw.get("username", ""),
            password_env=raw.get("passwordEnv") or env_key(name, "PASSWORD"),
            token_env=raw.get("tokenEnv") or env_key(name, "TOKEN"),
            expected_role=raw.get("expectedRole", ""),
            description=raw.get("description", ""),
            expects_permissions=list(raw.get("expectsPermissions", [])),
            lacks_permissions=list(raw.get("lacksPermissions", [])),
            probe_prompt=raw.get("probePrompt", ""),
            admin=bool(raw.get("admin", False)),
        )


def load_personas(path, wanted):
    file = Path(path)
    if not file.is_file():
        raise SystemExit(f"persona config not found: {file} (see {DEFAULT_PERSONAS})")
    text = file.read_text()
    if file.suffix.lower() in (".yaml", ".yml"):
        try:
            import yaml  # optional; JSON is always supported and needs no dependency
        except ImportError:
            raise SystemExit(
                f"{file} is YAML but PyYAML is not installed. Convert the file to JSON or "
                "`pip install --user pyyaml`."
            )
        raw = yaml.safe_load(text)
    else:
        raw = json.loads(text)
    entries = raw.get("personas") if isinstance(raw, dict) else raw
    if not entries:
        raise SystemExit(f"{file} defines no personas")
    personas = [Persona.from_dict(entry) for entry in entries]
    if wanted:
        by_name = {p.name: p for p in personas}
        missing = [name for name in wanted if name not in by_name]
        if missing:
            raise SystemExit(f"--persona {', '.join(missing)} not present in {file}")
        personas = [by_name[name] for name in wanted]
    return personas


def load_queries(path):
    queries = {key: list(value) for key, value in DEFAULT_QUERIES.items()}
    if not path:
        return queries
    file = Path(path)
    if not file.is_file():
        raise SystemExit(f"--queries file not found: {file}")
    override = json.loads(file.read_text())
    for suite, items in override.items():
        if suite not in SUITES:
            raise SystemExit(f"--queries file has unknown suite key '{suite}'")
        queries[suite] = list(items)
    return queries


def load_write_target(path):
    """The #1218 write target — plan decision C, deliberately not defaulted.

    Shape: {"prompt": "...", "expectedTool": "...", "clientContext": {...},
            "cleanupNote": "how to undo this on alpha"}
    """
    file = Path(path)
    if not file.is_file():
        raise SystemExit(f"--write-target file not found: {file}")
    target = json.loads(file.read_text())
    prompt = target.get("prompt")
    if not prompt:
        raise SystemExit(f"--write-target {file} must define a non-empty 'prompt'")
    if "<" in prompt and ">" in prompt:
        raise SystemExit(
            f"--write-target {file} still contains a template placeholder in 'prompt'. The #1218 "
            "write target is an open decision (plan decision C): choose a real downstream action, "
            "agree that dirtying alpha with it is acceptable, and record it in the file.")
    return target


# --- HTTP plumbing -------------------------------------------------------------------------------
@dataclass
class RequestPlan:
    label: str
    method: str
    url: str
    impact: str = READ
    headers: dict = field(default_factory=dict)
    body: object = None
    stream: bool = False
    persona: str = ""
    note: str = ""

    def describe(self):
        lines = [f"{self.method} {self.url}    [{self.impact}]"]
        if self.persona:
            lines.append(f"    as persona : {self.persona}")
        for key, value in sorted(redact(self.headers).items()):
            lines.append(f"    header     : {key}: {value}")
        if self.body is not None:
            lines.append(f"    body       : {json.dumps(redact_body(self.body))}")
        if self.note:
            lines.append(f"    note       : {self.note}")
        return "\n".join(lines)

    def to_json(self):
        return {
            "label": self.label,
            "method": self.method,
            "url": self.url,
            "impact": self.impact,
            "persona": self.persona,
            "headers": redact(self.headers),
            "body": redact_body(self.body),
            "stream": self.stream,
            "note": self.note,
        }


@dataclass
class HttpResult:
    plan: RequestPlan
    status: int = 0
    headers: dict = field(default_factory=dict)
    text: str = ""
    json_body: object = None
    error: str = ""
    started: object = None
    ended: object = None
    elapsed_ms: float = 0.0
    first_token_ms: float = None
    sse_events: int = 0

    @property
    def ok(self):
        return 200 <= self.status < 300


class Runner:
    """Executes RequestPlans, honouring --dry-run / --read-only / --allow-writes and recording
    everything it did (or refused to do) for the JSON artefact."""

    def __init__(self, args):
        self.args = args
        self.records = []
        self.skipped_for_safety = []

    def allowed(self, plan):
        if self.args.read_only and plan.impact != READ:
            return False, "--read-only: only READ requests execute"
        if plan.impact in (BUSINESS_WRITE, ADMIN_WRITE) and not self.args.allow_writes:
            return False, f"{plan.impact} requires --allow-writes (default is non-mutating)"
        return True, ""

    def send(self, plan):
        allowed, reason = self.allowed(plan)
        if not allowed:
            self.skipped_for_safety.append({"plan": plan.to_json(), "reason": reason})
            if self.args.verbose:
                print(f"  SKIP  {plan.method} {plan.url} — {reason}")
            return None
        result = http_call(plan, self.args.timeout)
        self.records.append({
            "plan": plan.to_json(),
            "status": result.status,
            "elapsedMs": round(result.elapsed_ms, 1),
            "firstTokenMs": None if result.first_token_ms is None else round(result.first_token_ms, 1),
            "error": result.error,
        })
        if self.args.verbose:
            marker = result.status or "ERR"
            print(f"  {marker:>5}  {plan.method} {plan.url} ({result.elapsed_ms:.0f} ms)")
        return result


def http_call(plan, timeout):
    """Perform one request. Non-2xx and transport errors are captured, never raised, so a suite can
    assert on a fail-closed 403 the same way it asserts on a 200."""
    data = None
    headers = dict(plan.headers)
    if plan.body is not None:
        data = json.dumps(plan.body).encode()
        headers.setdefault("Content-Type", "application/json")
    if plan.stream:
        headers.setdefault("Accept", "text/event-stream")
    else:
        headers.setdefault("Accept", "application/json")
    request = urllib.request.Request(plan.url, data=data, headers=headers, method=plan.method)
    result = HttpResult(plan=plan, started=now_utc())
    began = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            result.status = response.status
            result.headers = {k.lower(): v for k, v in response.headers.items()}
            if plan.stream:
                tokens = []
                for raw in response:
                    line = raw.decode("utf-8", "replace").rstrip("\n")
                    if line.startswith("data:"):
                        if result.first_token_ms is None:
                            result.first_token_ms = (time.monotonic() - began) * 1000.0
                        tokens.append(line[5:].lstrip())
                        result.sse_events += 1
                result.text = "".join(tokens)
            else:
                result.text = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        result.status = exc.code
        result.headers = {k.lower(): v for k, v in exc.headers.items()} if exc.headers else {}
        try:
            result.text = exc.read().decode("utf-8", "replace")
        except OSError:
            result.text = ""
    except (urllib.error.URLError, OSError, TimeoutError) as exc:
        result.error = str(exc)
    result.elapsed_ms = (time.monotonic() - began) * 1000.0
    result.ended = now_utc()
    if result.text and not plan.stream:
        try:
            result.json_body = json.loads(result.text)
        except ValueError:
            result.json_body = None
    return result


# --- Loki / telemetry ----------------------------------------------------------------------------
class LokiClient:
    """Harvests `nlti.request.telemetry` (and the tuning shadow logger) via LogQL query_range.

    Plan decision H: Loki is the harvest path. `observability/loki-config.yml` runs single-binary
    with auth_enabled=false, so no tenant header is needed by default; --loki-tenant sets
    X-Scope-OrgID for deployments that enable multi-tenancy.
    """

    def __init__(self, args):
        self.base = args.loki_url.rstrip("/")
        self.selector = args.loki_selector or '{%s="%s"}' % (args.loki_label, args.loki_service)
        self.tenant = args.loki_tenant
        self.timeout = args.timeout
        self.limit = args.loki_limit

    def query_range(self, logql, start, end):
        params = {
            "query": logql,
            "start": str(int(start.timestamp() * 1_000_000_000)),
            "end": str(int(end.timestamp() * 1_000_000_000)),
            "limit": str(self.limit),
            "direction": "backward",
        }
        url = f"{self.base}/loki/api/v1/query_range?{urllib.parse.urlencode(params)}"
        headers = {"Accept": "application/json"}
        if self.tenant:
            headers["X-Scope-OrgID"] = self.tenant
        plan = RequestPlan(label="loki-query", method="GET", url=url, impact=READ, headers=headers)
        result = http_call(plan, self.timeout)
        if result.error or not result.ok:
            raise LokiError(f"Loki query failed ({result.status or 'no response'}): "
                            f"{result.error or result.text[:200]}")
        body = result.json_body or {}
        lines = []
        for stream in body.get("data", {}).get("result", []):
            for entry in stream.get("values", []):
                if len(entry) >= 2:
                    lines.append((int(entry[0]), entry[1]))
        lines.sort()
        return lines

    def events(self, event_type, start, end, extra_filter=None):
        logql = f'{self.selector} |= "{event_type}"'
        if extra_filter:
            logql += f' |= "{extra_filter}"'
        records = []
        for ts_ns, line in self.query_range(logql, start, end):
            for obj in extract_json_objects(line):
                if obj.get("eventType") == event_type:
                    obj["_lokiTimestampNs"] = ts_ns
                    records.append(obj)
        return records

    def telemetry(self, start, end, extra_filter=None):
        return self.events(TELEMETRY_EVENT_TYPE, start, end, extra_filter)

    def lines(self, contains, start, end):
        logql = f'{self.selector} |= "{contains}"'
        return [line for _, line in self.query_range(logql, start, end)]


class LokiError(RuntimeError):
    pass


class TelemetryHarvester:
    def __init__(self, loki, args):
        self.loki = loki
        self.args = args
        self.errors = []

    def _poll(self, start, end, extra_filter, predicate, event_type=TELEMETRY_EVENT_TYPE,
              wait_seconds=None):
        """Poll Loki until a matching record lands or the wait budget is exhausted.

        promtail tails the docker json log and pushes on an interval, so a record written at t is
        not queryable at t; a bounded poll is required, not a single shot. `wait_seconds` overrides
        --telemetry-wait-seconds (used to keep the window-fallback poll short after the primary
        correlationId poll has already waited the full budget).
        """
        budget = self.args.telemetry_wait_seconds if wait_seconds is None else wait_seconds
        deadline = time.monotonic() + budget
        pad = self.args.telemetry_window_pad_seconds
        window_start = start - _seconds(pad)
        attempt = 0
        while True:
            attempt += 1
            window_end = now_utc() + _seconds(pad)
            try:
                records = self.loki.events(event_type, window_start, window_end, extra_filter)
            except LokiError as exc:
                self.errors.append(str(exc))
                return []
            hits = [record for record in records if predicate(record)]
            if hits or time.monotonic() >= deadline:
                if not hits and self.args.verbose:
                    print(f"  telemetry: no match after {attempt} poll(s) ({budget}s)")
                return hits
            time.sleep(min(2.0, max(0.5, self.args.telemetry_poll_seconds)))

    def by_correlation(self, correlation_id, start, end, wait_seconds=None):
        hits = self._poll(start, end, str(correlation_id),
                          lambda record: record.get("correlationId") == str(correlation_id),
                          wait_seconds=wait_seconds)
        if not hits:
            return None
        hits[0]["_join"] = "correlationId"
        return hits[0]

    def transition_by_correlation(self, correlation_id, start, end):
        """Gate 2C: the dedicated `nlti.workflow.transition` event, joined by the X-Correlation-Id
        the harness sent on POST /v1/nlt/sessions/{id}/workflow-state."""
        hits = self._poll(start, end, str(correlation_id),
                          lambda record: record.get("correlationId") == str(correlation_id),
                          event_type=TRANSITION_EVENT_TYPE)
        if not hits:
            return None
        hits[0]["_join"] = "correlationId"
        return hits[0]

    def for_window(self, start, end, role=None, wait_seconds=None):
        """Legacy time-window + actor-role join. Since the correlation servlet filter, this is
        ONLY the explicit fallback for servers that predate it (see chat_record): the 2026-08-19
        live run proved this join can attribute the PREVIOUS persona's record, so results based on
        it must be reported UNVERIFIED-ATTRIBUTION, never as solid evidence."""
        start_ns = int(start.timestamp() * 1_000_000_000)
        end_ns = int(end.timestamp() * 1_000_000_000)
        pad_ns = int(self.args.telemetry_window_pad_seconds * 1_000_000_000)

        def in_window(record):
            ts_ns = record.get("_lokiTimestampNs", 0)
            if not (start_ns - pad_ns <= ts_ns <= end_ns + pad_ns):
                return False
            if role and (record.get("actor") or {}).get("primaryRole") != role:
                return False
            return True

        hits = self._poll(start, end, None, in_window, wait_seconds=wait_seconds)
        if not hits:
            return None
        # closest to the request end is the turn we just issued
        hits.sort(key=lambda record: abs(record.get("_lokiTimestampNs", 0) - end_ns))
        hits[0]["_join"] = "window"
        return hits[0]

    def chat_record(self, correlation_id, start, end, role=None):
        """Chat-path join (2026-08-19 live-run defect 1). The harness sends a generated
        X-Correlation-Id on /v1/mcp/chat and /v1/mcp/chat/stream and joins primarily by it — the
        correlation servlet filter echoes the header on every endpoint. When no harvested record
        carries the sent id the server predates the filter; the legacy time-window+role join then
        runs as an explicit fallback and the record is tagged _join="window-fallback" so every
        dependent check is downgraded to UNVERIFIED-ATTRIBUTION."""
        hit = self.by_correlation(correlation_id, start, end)
        if hit is not None:
            return hit
        record = self.for_window(start, end, role,
                                 wait_seconds=min(self.args.telemetry_wait_seconds, 5.0))
        if record is not None:
            record["_join"] = "window-fallback"
        return record


def _seconds(value):
    from datetime import timedelta
    return timedelta(seconds=value)


# --- telemetry field accessors (schema: internal/telemetry/NltiRequestTelemetry) -----------------
def tel_tools(record):
    return sorted(((record or {}).get("tools") or {}).get("selected") or [])


def tel_prompt_layers(record):
    return list(((record or {}).get("rag") or {}).get("promptLayers") or [])


def tel_rag_docs(record):
    return sorted(doc.get("docId") for doc in (((record or {}).get("rag") or {}).get("retrieved") or []))


def tel_workflow(record):
    return ((record or {}).get("routing") or {}).get("workflowState")


def tel_tier(record):
    return ((record or {}).get("routing") or {}).get("tier")


def tel_role(record):
    return ((record or {}).get("actor") or {}).get("primaryRole")


def tel_perm_count(record):
    return ((record or {}).get("actor") or {}).get("permissionCodeCount")


def tel_status(record):
    return ((record or {}).get("outcome") or {}).get("status")


def tel_total_ms(record):
    return ((record or {}).get("latency") or {}).get("totalMs")


def tel_fallback(record):
    return ((record or {}).get("model") or {}).get("fallbackUsed")


def tel_is_write(record):
    return ((record or {}).get("write") or {}).get("isWrite")


def tel_unsupported(record):
    return ((record or {}).get("quality") or {}).get("unsupportedAnswerFlag")


# --- results -------------------------------------------------------------------------------------
@dataclass
class Check:
    id: str
    label: str
    status: str
    evidence: str = ""


@dataclass
class Metric:
    name: str
    current: object = None
    unit: str = ""
    verdict: str = "—"
    note: str = ""


@dataclass
class SuiteResult:
    name: str
    gate: str
    issue: str
    title: str
    checks: list = field(default_factory=list)
    metrics: list = field(default_factory=list)
    observations: dict = field(default_factory=dict)
    notes: list = field(default_factory=list)

    def add(self, check_id, label, status, evidence=""):
        self.checks.append(Check(check_id, label, status, evidence))

    def expect(self, check_id, label, condition, evidence):
        self.add(check_id, label, PASS if condition else FAIL, evidence)

    def expect_joined(self, check_id, label, condition, evidence, record):
        """expect(), except the evidence came from a harvested telemetry record: when that record
        was joined by the time-window+role fallback (no harvested record carried the sent
        X-Correlation-Id) the check is reported UNVERIFIED-ATTRIBUTION instead of PASS/FAIL, so a
        fallback-joined check can never be read as solid evidence."""
        if (record or {}).get("_join") == "window-fallback":
            verdict = PASS if condition else FAIL
            self.add(check_id, label, UNVERIFIED,
                     "UNVERIFIED-ATTRIBUTION (telemetry joined by the time-window+actor-role "
                     "fallback; no harvested record carried the sent X-Correlation-Id, so the "
                     "server predates the correlation filter and the record may belong to another "
                     f"request) would-be {verdict} — {evidence}")
        else:
            self.expect(check_id, label, condition, evidence)

    def skip(self, check_id, label, reason):
        self.add(check_id, label, SKIP, f"SKIPPED — {reason}")

    @property
    def counts(self):
        return {
            PASS: sum(1 for c in self.checks if c.status == PASS),
            FAIL: sum(1 for c in self.checks if c.status == FAIL),
            SKIP: sum(1 for c in self.checks if c.status == SKIP),
            UNVERIFIED: sum(1 for c in self.checks if c.status == UNVERIFIED),
        }

    @property
    def status(self):
        counts = self.counts
        if counts[FAIL]:
            return FAIL
        if counts[UNVERIFIED]:
            return UNVERIFIED
        if counts[PASS] == 0:
            return SKIP
        return PASS


# --- context -------------------------------------------------------------------------------------
class Context:
    def __init__(self, args, personas, queries, runner, harvester):
        self.args = args
        self.personas = personas
        self.queries = queries
        self.runner = runner
        self.harvester = harvester
        self.tokens = {}

    # -- URL building: gateway contract is X-API-Version + path rewrite /{domain}/vN/... ----------
    def mcp_url(self, path):
        path = path.lstrip("/")
        base = self.args.gateway_url.rstrip("/")
        if self.args.already_versioned:
            return f"{base}/{self.args.service_route}/v{self.args.api_version}/{path}"
        return f"{base}/{self.args.service_route}/{path}"

    def auth_url(self):
        # /security-service/v1/auth/** bypasses the gateway's version rewrite
        # (pos-api-gateway application.yml: auth.auth-path-prefix), so it is always explicit /v1.
        return f"{self.args.gateway_url.rstrip('/')}/{self.args.security_route}/v1/auth/login"

    def headers(self, persona, extra=None):
        headers = {"X-API-Version": str(self.args.api_version)}
        token = self.tokens.get(persona.name)
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if extra:
            headers.update(extra)
        return headers

    # -- plan constructors: shared by --dry-run (plan()) and the live run (run()) -----------------
    def plan_login(self, persona):
        return RequestPlan(
            label=f"login:{persona.name}",
            method="POST",
            url=self.auth_url(),
            impact=READ,
            persona=persona.name,
            body={"username": persona.username, "password": f"${{{persona.password_env}}}"},
            note=f"skipped when ${persona.token_env} supplies a token directly",
        )

    def plan_chat(self, persona, prompt, label="chat", correlation_id=None):
        extra = {"X-Correlation-Id": str(correlation_id)} if correlation_id else None
        return RequestPlan(
            label=label,
            method="POST",
            url=self.mcp_url("mcp/chat"),
            impact=SESSION,
            persona=persona.name,
            headers=self.headers(persona, extra),
            body={"message": prompt},
            note="McpChatController#chat — requires mcp:chat:execute; the X-Correlation-Id keys "
                 "the telemetry join on servers with the correlation filter",
        )

    def plan_stream(self, persona, prompt, label="chat-stream", correlation_id=None):
        extra = {"Accept": "text/event-stream"}
        if correlation_id:
            extra["X-Correlation-Id"] = str(correlation_id)
        return RequestPlan(
            label=label,
            method="POST",
            url=self.mcp_url("mcp/chat/stream"),
            impact=SESSION,
            persona=persona.name,
            headers=self.headers(persona, extra),
            body={"message": prompt},
            stream=True,
            note="McpStreamingChatController#streamChat — requires mcp:chat:stream; the "
                 "X-Correlation-Id keys the telemetry join on servers with the correlation filter",
        )

    def plan_permission_probe(self, persona, permission, expectation):
        """Persona-suite probe proving a permission is held ('expects') or absent ('lacks'):
        telemetry carries only actor.permissionCodeCount, so the endpoint class the code gates is
        probed instead — @PreAuthorize rejects with 403 before the handler runs."""
        method, probe_path, impact, body, stream, note = PERMISSION_PROBES[permission]
        probe_path = probe_path.replace("{tool}", self.args.admin_tool_name)
        extra = {"Accept": "text/event-stream"} if stream else None
        expected = "non-401/403 proves the code is held" if expectation == "expects" \
            else "403 proves the code is absent"
        return RequestPlan(
            label=f"perm-{expectation}:{permission}",
            method=method,
            url=self.mcp_url(probe_path),
            impact=impact,
            persona=persona.name,
            headers=self.headers(persona, extra),
            body=body,
            stream=stream,
            note=f"{note} — {expectation}Permissions probe for '{permission}' ({expected})",
        )

    def plan_nlti_submit(self, persona, prompt, correlation_id, session_id=None, client_context=None):
        body = {"prompt": prompt}
        if session_id:
            body["sessionId"] = str(session_id)
        if client_context:
            body["clientContext"] = client_context
        return RequestPlan(
            label="nlti-submit",
            method="POST",
            url=self.mcp_url("nlt/requests"),
            impact=SESSION,
            persona=persona.name,
            headers=self.headers(persona, {"X-Correlation-Id": str(correlation_id)}),
            body=body,
            note="NltiController#submitRequest — previews a write plan, never executes it",
        )

    def plan_workflow_state(self, persona, session_id, state, correlation_id=None):
        extra = {"X-Correlation-Id": str(correlation_id)} if correlation_id else None
        return RequestPlan(
            label=f"workflow-state:{state}",
            method="POST",
            url=self.mcp_url(f"nlt/sessions/{session_id}/workflow-state"),
            impact=SESSION,
            persona=persona.name,
            headers=self.headers(persona, extra),
            body={"workflowState": state},
            note="NltiController#setWorkflowState — ownership-checked, session-scoped; the "
                 "X-Correlation-Id keys the nlti.workflow.transition telemetry event",
        )

    def plan_confirm(self, persona, request_id, idempotency_key=None):
        return RequestPlan(
            label="write-plan-confirm",
            method="POST",
            url=self.mcp_url(f"nlt/requests/{request_id}/confirm"),
            impact=BUSINESS_WRITE,
            persona=persona.name,
            headers=self.headers(persona),
            body=({"idempotencyKey": idempotency_key} if idempotency_key else None),
            note="EXECUTES the target tool — gated behind --allow-writes + --write-target",
        )

    def plan_cancel(self, persona, request_id):
        return RequestPlan(
            label="write-plan-cancel",
            method="POST",
            url=self.mcp_url(f"nlt/requests/{request_id}/cancel"),
            impact=SESSION,
            persona=persona.name,
            headers=self.headers(persona),
            note="NltiController#cancelWritePlan — idempotent, never executes",
        )

    def plan_audit(self, persona, correlation_id=None):
        query = f"?correlationId={correlation_id}" if correlation_id else ""
        return RequestPlan(
            label="audit-query",
            method="GET",
            url=self.mcp_url(f"nlt/audit{query}"),
            impact=READ,
            persona=persona.name,
            headers=self.headers(persona),
            note="AuditController#queryAudit — requires nlti:audit:read",
        )

    def plan_get(self, persona, path, label, note):
        return RequestPlan(
            label=label,
            method="GET",
            url=self.mcp_url(path),
            impact=READ,
            persona=persona.name,
            headers=self.headers(persona),
            note=note,
        )

    # -- auth ------------------------------------------------------------------------------------
    def authenticate(self, persona):
        if persona.name in self.tokens:
            return self.tokens[persona.name]
        token = os.environ.get(persona.token_env)
        if not token and persona.expected_role:
            token = os.environ.get(f"POS_MCP_TOKEN_{persona.expected_role}")
        if not token:
            token = env_file(persona.token_env, self.args.env_file)
        if token:
            self.tokens[persona.name] = token
            return token
        password = os.environ.get(persona.password_env) or env_file(persona.password_env,
                                                                    self.args.env_file)
        if not persona.username or not password:
            raise ConfigError(
                f"persona '{persona.name}': no token in ${persona.token_env} and no password in "
                f"${persona.password_env} (username='{persona.username}')")
        plan = self.plan_login(persona)
        plan.body = {"username": persona.username, "password": password}
        result = self.runner.send(plan)
        if result is None:
            raise ConfigError(f"persona '{persona.name}': login refused by the safety gate")
        if not result.ok or not isinstance(result.json_body, dict):
            raise InfraError(f"persona '{persona.name}': login failed "
                             f"({result.status or result.error}) at {plan.url}")
        token = result.json_body.get("accessToken")
        if not token:
            raise InfraError(f"persona '{persona.name}': login response carried no accessToken")
        self.tokens[persona.name] = token
        return token


class ConfigError(RuntimeError):
    pass


class InfraError(RuntimeError):
    pass


def uuid7ish():
    """A random UUIDv4 is sufficient as a client-supplied correlation id: NltiCorrelationIdSupport
    only requires it to parse as a UUID."""
    import uuid
    return str(uuid.uuid4())


def answer_of(result):
    if result is None:
        return ""
    if result.plan.stream:
        return result.text
    body = result.json_body or {}
    return body.get("response", "") if isinstance(body, dict) else ""


def looks_like_refusal(text):
    lowered = (text or "").lower()
    return any(marker in lowered for marker in REFUSAL_MARKERS)


# --- suites: plan() (offline, for --dry-run) + run() (live) ---------------------------------------
def plan_equivalence(ctx):
    plans = []
    for persona in ctx.personas:
        plans.append(ctx.plan_login(persona))
        for query in ctx.queries["equivalence"]:
            plans.append(ctx.plan_chat(persona, query["prompt"], f"eq-blocking:{query['id']}",
                                       "<generated-uuid>"))
            plans.append(ctx.plan_stream(persona, query["prompt"], f"eq-streaming:{query['id']}",
                                         "<generated-uuid>"))
    return plans


def run_equivalence(ctx):
    gate, issue, title = SUITES["equivalence"]
    suite = SuiteResult("equivalence", gate, issue, title)
    blocking_ms, streaming_ms, ttft_ms = [], [], []
    pairs = []

    for persona in ctx.personas:
        try:
            ctx.authenticate(persona)
        except (ConfigError, InfraError) as exc:
            suite.skip(f"eq-auth:{persona.name}", "Persona authenticated through the gateway",
                       str(exc))
            continue
        role = persona.expected_role or None
        for query in ctx.queries["equivalence"]:
            case = f"{query['id']}@{persona.name}"
            blocking_cid = uuid7ish()
            blocking = ctx.runner.send(ctx.plan_chat(persona, query["prompt"],
                                                     f"eq-blocking:{query['id']}", blocking_cid))
            tel_block = None
            if blocking is not None and blocking.ok:
                blocking_ms.append(blocking.elapsed_ms)
                tel_block = ctx.harvester.chat_record(blocking_cid, blocking.started,
                                                      blocking.ended, role)
            streaming_cid = uuid7ish()
            streaming = ctx.runner.send(ctx.plan_stream(persona, query["prompt"],
                                                        f"eq-streaming:{query['id']}",
                                                        streaming_cid))
            tel_stream = None
            if streaming is not None and streaming.ok:
                streaming_ms.append(streaming.elapsed_ms)
                if streaming.first_token_ms is not None:
                    ttft_ms.append(streaming.first_token_ms)
                tel_stream = ctx.harvester.chat_record(streaming_cid, streaming.started,
                                                       streaming.ended, role)

            if blocking is None or streaming is None:
                suite.skip(f"eq-http:{case}", "Both endpoints answered the same request",
                           "request refused by the safety gate")
                continue
            suite.expect(f"eq-http:{case}", "Both endpoints answered the same request",
                         blocking.ok and streaming.ok,
                         f"blocking HTTP {blocking.status or blocking.error}, "
                         f"streaming HTTP {streaming.status or streaming.error} "
                         f"({streaming.sse_events} SSE events)")
            if not (tel_block and tel_stream):
                suite.skip(f"eq-telemetry:{case}", "Telemetry harvested for both endpoints",
                           "no nlti.request.telemetry record matched in Loki for the request window")
                continue

            fallback = (tel_block.get("_join") == "window-fallback"
                        or tel_stream.get("_join") == "window-fallback")
            joined = {"_join": "window-fallback"} if fallback else tel_block
            pairs.append({"case": case, "blocking": tel_block, "streaming": tel_stream,
                          "telemetryJoin": "window-fallback" if fallback else "correlationId"})
            suite.expect_joined(f"eq-tools:{case}", "Same candidate tools",
                                tel_tools(tel_block) == tel_tools(tel_stream),
                                f"blocking={tel_tools(tel_block)} streaming={tel_tools(tel_stream)}",
                                joined)
            suite.expect_joined(f"eq-layers:{case}", "Same prompt layers",
                                tel_prompt_layers(tel_block) == tel_prompt_layers(tel_stream),
                                f"blocking={tel_prompt_layers(tel_block)} "
                                f"streaming={tel_prompt_layers(tel_stream)}", joined)
            suite.expect_joined(f"eq-persona:{case}", "Same persona",
                                tel_role(tel_block) == tel_role(tel_stream)
                                and tel_perm_count(tel_block) == tel_perm_count(tel_stream),
                                f"role={tel_role(tel_block)}/{tel_role(tel_stream)} "
                                f"permCount={tel_perm_count(tel_block)}/{tel_perm_count(tel_stream)}",
                                joined)
            suite.expect_joined(f"eq-ragscope:{case}", "Same RAG scope",
                                tel_rag_docs(tel_block) == tel_rag_docs(tel_stream),
                                f"retrieved docIds blocking={tel_rag_docs(tel_block)} "
                                f"streaming={tel_rag_docs(tel_stream)} "
                                "(telemetry carries retrieved docIds, not a ragScope field — docId "
                                "set is the scope proxy)", joined)
            suite.expect_joined(f"eq-workflow:{case}", "Same workflow state",
                                tel_workflow(tel_block) == tel_workflow(tel_stream),
                                f"blocking={tel_workflow(tel_block)} "
                                f"streaming={tel_workflow(tel_stream)}", joined)
            suite.expect_joined(f"eq-tier:{case}", "Same routing tier",
                                tel_tier(tel_block) == tel_tier(tel_stream),
                                f"blocking={tel_tier(tel_block)} streaming={tel_tier(tel_stream)}",
                                joined)
            suite.expect_joined(f"eq-outcome:{case}",
                                "Telemetry distinguishes endpoint type while showing "
                                "equivalent decisions",
                                tel_status(tel_block) == "SUCCESS"
                                and tel_status(tel_stream) == "SUCCESS",
                                f"outcome blocking={tel_status(tel_block)} "
                                f"streaming={tel_status(tel_stream)}", joined)

    suite.metrics = [
        Metric("Blocking p50 latency", percentile(blocking_ms, 50), "ms"),
        Metric("Blocking p95 latency", percentile(blocking_ms, 95), "ms"),
        Metric("Streaming p50 latency", percentile(streaming_ms, 50), "ms"),
        Metric("Streaming p95 latency", percentile(streaming_ms, 95), "ms"),
        Metric("Streaming p95 time-to-first-token", percentile(ttft_ms, 95), "ms"),
        Metric("Equivalent request pairs compared", len(pairs), "pairs"),
    ]
    suite.observations["pairs"] = [
        {"case": pair["case"],
         "telemetryJoin": pair["telemetryJoin"],
         "blockingTools": tel_tools(pair["blocking"]),
         "streamingTools": tel_tools(pair["streaming"]),
         "blockingLayers": tel_prompt_layers(pair["blocking"]),
         "streamingLayers": tel_prompt_layers(pair["streaming"])}
        for pair in pairs
    ]
    suite.notes.append(
        "Chat telemetry is joined by the X-Correlation-Id the harness sends on every request. "
        "Records that only matched the legacy time-window+role fallback (server predating the "
        "correlation filter) downgrade their checks to UNVERIFIED-ATTRIBUTION: the 2026-08-19 "
        "live run proved that join can attribute the previous persona's record.")
    return suite


def plan_persona(ctx):
    plans = []
    for persona in ctx.personas:
        plans.append(ctx.plan_login(persona))
        prompt = persona.probe_prompt or ctx.queries["persona"][0]["prompt"]
        plans.append(ctx.plan_chat(persona, prompt, f"persona:{persona.name}",
                                   "<generated-uuid>"))
        for perm in persona.expects_permissions:
            if perm != "mcp:chat:execute" and perm in PERMISSION_PROBES:
                plans.append(ctx.plan_permission_probe(persona, perm, "expects"))
        for perm in persona.lacks_permissions:
            if perm in PERMISSION_PROBES:
                plans.append(ctx.plan_permission_probe(persona, perm, "lacks"))
    return plans


def _persona_permission_checks(ctx, suite, persona, chat_result):
    """2026-08-19 live-run defect 2: expectsPermissions/lacksPermissions were parsed but never
    asserted. NltiRequestTelemetry.Actor emits only (primaryRole, permissionCodeCount) — never the
    permission-code list — so every declared code is verified by probing the endpoint class it
    gates: @PreAuthorize rejects with 401/403 before the handler, so any other status proves the
    authority is held and a 403 proves it is absent."""
    for perm in persona.expects_permissions:
        check_id = f"persona-expects:{persona.name}:{perm}"
        label = f"Persona holds '{perm}'"
        if perm == "mcp:chat:execute" and chat_result is not None:
            suite.expect(check_id, label,
                         bool(chat_result.status) and chat_result.status not in (401, 403),
                         f"persona chat turn HTTP {chat_result.status or chat_result.error} "
                         "(non-401/403 proves mcp:chat:execute; probe reused, no extra request)")
            continue
        if perm not in PERMISSION_PROBES:
            suite.skip(check_id, label,
                       f"no read-safe probe mapped for '{perm}' and telemetry carries only "
                       "actor.permissionCodeCount, not the code list")
            continue
        probe = ctx.runner.send(ctx.plan_permission_probe(persona, perm, "expects"))
        if probe is None:
            suite.skip(check_id, label, "probe refused by the safety gate")
            continue
        suite.expect(check_id, label,
                     bool(probe.status) and probe.status not in (401, 403),
                     f"{probe.plan.method} {probe.plan.url} -> "
                     f"HTTP {probe.status or probe.error} (any non-401/403 status proves the "
                     "authority: @PreAuthorize rejects before the handler)")
    for perm in persona.lacks_permissions:
        check_id = f"persona-lacks:{persona.name}:{perm}"
        label = f"Persona is denied '{perm}' (403)"
        if perm not in PERMISSION_PROBES:
            suite.skip(check_id, label,
                       f"no read-safe probe mapped for '{perm}' and telemetry carries only "
                       "actor.permissionCodeCount, not the code list")
            continue
        probe = ctx.runner.send(ctx.plan_permission_probe(persona, perm, "lacks"))
        if probe is None:
            suite.skip(check_id, label, "probe refused by the safety gate")
            continue
        suite.expect(check_id, label, probe.status == 403,
                     f"{probe.plan.method} {probe.plan.url} -> "
                     f"HTTP {probe.status or probe.error} (403 required to prove the permission "
                     "is absent)")


def run_persona(ctx):
    gate, issue, title = SUITES["persona"]
    suite = SuiteResult("persona", gate, issue, title)
    layers_by_persona = {}
    fallback_personas = []
    answers = []
    refusals = 0
    unsupported = 0
    answered = 0

    for persona in ctx.personas:
        try:
            ctx.authenticate(persona)
        except (ConfigError, InfraError) as exc:
            suite.skip(f"persona-auth:{persona.name}", "Persona authenticated through the gateway",
                       str(exc))
            continue
        prompt = persona.probe_prompt or ctx.queries["persona"][0]["prompt"]
        chat_cid = uuid7ish()
        result = ctx.runner.send(ctx.plan_chat(persona, prompt, f"persona:{persona.name}",
                                               chat_cid))
        if result is None:
            suite.skip(f"persona-http:{persona.name}", "Persona chat turn executed",
                       "request refused by the safety gate")
            _persona_permission_checks(ctx, suite, persona, None)
            continue
        suite.expect(f"persona-http:{persona.name}", "Persona chat turn executed", result.ok,
                     f"HTTP {result.status or result.error}")
        _persona_permission_checks(ctx, suite, persona, result)
        answer = answer_of(result)
        record = ctx.harvester.chat_record(chat_cid, result.started, result.ended,
                                           persona.expected_role or None)
        if record is None:
            suite.skip(f"persona-telemetry:{persona.name}", "Prompt-layer telemetry emitted",
                       "no nlti.request.telemetry record matched the sent correlationId or the "
                       "request window in Loki")
            continue
        answered += 1
        layers = tel_prompt_layers(record)
        layers_by_persona[persona.name] = layers
        if record.get("_join") == "window-fallback":
            fallback_personas.append(persona.name)
        suite.expect_joined(f"persona-layers:{persona.name}", "Prompt-layer telemetry emitted",
                            bool(layers), f"rag.promptLayers={layers}", record)
        suite.expect_joined(f"persona-layers-required:{persona.name}",
                            "Layered prompt carries BASE + ROLE",
                            all(layer in layers for layer in REQUIRED_PROMPT_LAYERS),
                            f"rag.promptLayers={layers} required={list(REQUIRED_PROMPT_LAYERS)}",
                            record)
        if persona.expected_role:
            suite.expect_joined(f"persona-role:{persona.name}",
                                "Telemetry actor matches the persona role",
                                tel_role(record) == persona.expected_role,
                                f"actor.primaryRole={tel_role(record)} "
                                f"expected={persona.expected_role}", record)
        suite.expect_joined(f"persona-perms:{persona.name}", "Permission-scoped actor recorded",
                            isinstance(tel_perm_count(record), int) and tel_perm_count(record) > 0,
                            f"actor.permissionCodeCount={tel_perm_count(record)}", record)
        if looks_like_refusal(answer):
            refusals += 1
        if tel_unsupported(record):
            unsupported += 1
        answers.append({
            "persona": persona.name,
            "telemetryJoin": record.get("_join"),
            "role": tel_role(record),
            "prompt": prompt,
            "answerChars": len(answer),
            "answer": answer if ctx.args.capture_answers else "<not captured; pass --capture-answers>",
            "promptLayers": layers,
            "toolsSelected": tel_tools(record),
            "unsupportedAnswerFlag": tel_unsupported(record),
            "looksLikeRefusal": looks_like_refusal(answer),
            "latencyMs": tel_total_ms(record),
        })

    if len(layers_by_persona) >= 2:
        distinct = {tuple(layers) for layers in layers_by_persona.values()}
        joined = ({"_join": "window-fallback"} if fallback_personas
                  else {"_join": "correlationId"})
        suite.expect_joined("persona-differentiation", "Role layer differentiates personas",
                            len(distinct) >= 1
                            and all(layers for layers in layers_by_persona.values()),
                            f"promptLayers per persona={layers_by_persona}", joined)
    else:
        suite.skip("persona-differentiation", "Role layer differentiates personas",
                   "fewer than two personas produced telemetry")

    suite.metrics = [
        Metric("Personas answered", answered, "personas"),
        Metric("Answers flagged unsupported", unsupported, "answers"),
        Metric("Answers that read as a refusal", refusals, "answers"),
    ]
    suite.observations["answers"] = answers
    suite.notes.append(
        "Answer quality is captured, not graded: run scripts/rag_gap_harness.py replay over the "
        "captured answers for source-grounded judging.")
    suite.notes.append(
        "expectsPermissions/lacksPermissions are verified by endpoint probes: "
        "NltiRequestTelemetry.Actor emits only (primaryRole, permissionCodeCount) — the "
        "permission-code list is not in telemetry — so a held code is proven by a non-401/403 "
        "response from the endpoint it gates and an absent code by a 403.")
    return suite


def plan_workflow(ctx):
    persona = ctx.personas[0]
    correlation = "<generated-uuid>"
    plans = [
        ctx.plan_login(persona),
        ctx.plan_nlti_submit(persona, ctx.queries["workflow"][0]["prompt"], correlation),
        ctx.plan_workflow_state(persona, "<sessionId from the submit response>", "IDLE",
                                "<generated-uuid>"),
        ctx.plan_chat(persona, ctx.queries["workflow"][0]["prompt"], "workflow-baseline-chat",
                      "<generated-uuid>"),
        ctx.plan_workflow_state(persona, "<sessionId>", ctx.args.workflow_state,
                                "<generated-uuid>"),
        ctx.plan_chat(persona, ctx.queries["workflow"][0]["prompt"], "workflow-gated-chat",
                      "<generated-uuid>"),
        ctx.plan_audit(persona, correlation),
    ]
    if len(ctx.personas) > 1:
        other = ctx.personas[1]
        plans.append(ctx.plan_login(other))
        plan = ctx.plan_workflow_state(other, f"<sessionId owned by {persona.name}>",
                                       ctx.args.workflow_state, "<generated-uuid>")
        plan.note = "ownership fail-closed probe — expects HTTP 403"
        plans.append(plan)
    return plans


def run_workflow(ctx):
    gate, issue, title = SUITES["workflow"]
    suite = SuiteResult("workflow", gate, issue, title)
    target_state = ctx.args.workflow_state
    persona = ctx.personas[0]
    ctx.authenticate(persona)
    role = persona.expected_role or None
    prompt = ctx.queries["workflow"][0]["prompt"]

    correlation = uuid7ish()
    submit = ctx.runner.send(ctx.plan_nlti_submit(persona, prompt, correlation))
    if submit is None or not submit.ok or not isinstance(submit.json_body, dict):
        suite.add("wf-session", "NLTI session established", FAIL,
                  f"POST /v1/nlt/requests returned {getattr(submit, 'status', 'refused')}")
        return suite
    session_id = submit.json_body.get("sessionId")
    suite.expect("wf-session", "NLTI session established", bool(session_id),
                 f"sessionId={session_id} correlationId={submit.json_body.get('correlationId')}")
    if not session_id:
        return suite

    baseline_state = "IDLE"
    idle_set = ctx.runner.send(
        ctx.plan_workflow_state(persona, session_id, baseline_state, uuid7ish()))
    idle_tools = []
    if idle_set is not None and idle_set.ok:
        idle_cid = uuid7ish()
        idle_chat = ctx.runner.send(ctx.plan_chat(persona, prompt, "workflow-baseline-chat",
                                                  idle_cid))
        if idle_chat is not None and idle_chat.ok:
            idle_record = ctx.harvester.chat_record(idle_cid, idle_chat.started,
                                                    idle_chat.ended, role)
            idle_tools = tel_tools(idle_record) if idle_record else []
            suite.observations["idle"] = {"tools": idle_tools,
                                          "workflowState": tel_workflow(idle_record)}

    advance_correlation = uuid7ish()
    advance = ctx.runner.send(
        ctx.plan_workflow_state(persona, session_id, target_state, advance_correlation))
    if advance is None:
        suite.skip("wf-advance", f"Session advanced to {target_state}",
                   "request refused by the safety gate")
        return suite
    echoed = (advance.json_body or {}).get("workflowState") if advance.ok else None
    suite.expect("wf-advance", f"Session advanced to {target_state}",
                 advance.ok and echoed == target_state,
                 f"HTTP {advance.status or advance.error} body.workflowState={echoed}")

    transition = ctx.harvester.transition_by_correlation(advance_correlation, advance.started,
                                                         advance.ended)
    if transition is None:
        suite.skip("wf-transition-event", "Workflow transition emitted as a dedicated event",
                   f"no {TRANSITION_EVENT_TYPE} record matched correlationId={advance_correlation}")
    else:
        moved = transition.get("transition") or {}
        suite.expect("wf-transition-event", "Workflow transition emitted as a dedicated event",
                     moved.get("toState") == target_state and moved.get("changed") is True,
                     f"{TRANSITION_EVENT_TYPE} fromState={moved.get('fromState')} "
                     f"toState={moved.get('toState')} changed={moved.get('changed')} "
                     f"sessionId={transition.get('sessionId')} "
                     f"correlationId={advance_correlation}")
        suite.observations["transition"] = moved

    gated_cid = uuid7ish()
    gated_chat = ctx.runner.send(ctx.plan_chat(persona, prompt, "workflow-gated-chat", gated_cid))
    gated_record = None
    if gated_chat is not None and gated_chat.ok:
        gated_record = ctx.harvester.chat_record(gated_cid, gated_chat.started,
                                                 gated_chat.ended, role)
    if gated_record is None:
        suite.skip("wf-telemetry", "Workflow state visible in telemetry",
                   "no nlti.request.telemetry record matched the sent correlationId or the "
                   "request window in Loki")
    else:
        gated_tools = tel_tools(gated_record)
        suite.expect_joined("wf-telemetry", "Workflow state visible in telemetry",
                            tel_workflow(gated_record) == target_state,
                            f"routing.workflowState={tel_workflow(gated_record)} "
                            f"expected={target_state}", gated_record)
        suite.expect_joined("wf-gated-tools",
                            f"Non-IDLE gated tool set activates for {target_state}",
                            bool(gated_tools),
                            f"tools.selected={gated_tools} (IDLE baseline={idle_tools})",
                            gated_record)
        added = sorted(set(gated_tools) - set(idle_tools))
        suite.expect_joined("wf-tool-delta", "Gated tool set differs from the IDLE baseline",
                            bool(added) or (bool(gated_tools) and not idle_tools),
                            f"tools added vs IDLE={added}", gated_record)
        suite.observations["gated"] = {"tools": gated_tools,
                                       "telemetryJoin": gated_record.get("_join"),
                                       "workflowState": tel_workflow(gated_record),
                                       "addedVsIdle": added}

    audit = ctx.runner.send(ctx.plan_audit(persona, correlation))
    if audit is None:
        suite.skip("wf-audit", "Audit ledger records the session activity",
                   "request refused by the safety gate")
    elif audit.status == 403:
        suite.skip("wf-audit", "Audit ledger records the session activity",
                   f"persona '{persona.name}' lacks nlti:audit:read (HTTP 403)")
    else:
        entries = ((audit.json_body or {}).get("content") or []) if audit.ok else []
        suite.expect("wf-audit", "Audit ledger records the session activity",
                     audit.ok and len(entries) > 0,
                     f"HTTP {audit.status or audit.error}, {len(entries)} entries for "
                     f"correlationId={correlation}")

    if len(ctx.personas) > 1:
        other = ctx.personas[1]
        try:
            ctx.authenticate(other)
        except (ConfigError, InfraError) as exc:
            suite.skip("wf-ownership", "Workflow-state change is ownership fail-closed", str(exc))
        else:
            plan = ctx.plan_workflow_state(other, session_id, target_state, uuid7ish())
            plan.note = "ownership fail-closed probe — expects HTTP 403"
            foreign = ctx.runner.send(plan)
            if foreign is None:
                suite.skip("wf-ownership", "Workflow-state change is ownership fail-closed",
                           "request refused by the safety gate")
            else:
                suite.expect("wf-ownership", "Workflow-state change is ownership fail-closed",
                             foreign.status == 403,
                             f"persona '{other.name}' on {persona.name}'s session -> "
                             f"HTTP {foreign.status or foreign.error} (expected 403)")
    else:
        suite.skip("wf-ownership", "Workflow-state change is ownership fail-closed",
                   "needs a second persona")

    suite.metrics = [
        Metric("Workflow state exercised", target_state),
        Metric("Gated tools activated", len(suite.observations.get("gated", {}).get("tools", [])),
               "tools"),
        Metric("Tools added vs IDLE", len(suite.observations.get("gated", {}).get("addedVsIdle", [])),
               "tools"),
    ]
    if target_state == "PROCESSING_RETURN":
        suite.notes.append(
            "PROCESSING_RETURN has no mcp_tool_workflow rows on main; this suite is only meaningful "
            "after the V34 seed from plan item 0.4 (decision D) is applied.")
    return suite


def plan_router(ctx):
    persona = ctx.personas[0]
    plans = [ctx.plan_login(persona)]
    for query in ctx.queries["router"]:
        plans.append(ctx.plan_chat(persona, query["prompt"], f"router:{query['id']}",
                                   "<generated-uuid>"))
    return plans


def run_router(ctx):
    gate, issue, title = SUITES["router"]
    suite = SuiteResult("router", gate, issue, title)
    persona = ctx.personas[0]
    ctx.authenticate(persona)
    role = persona.expected_role or None

    rows = []
    latencies = []
    for query in ctx.queries["router"]:
        query_cid = uuid7ish()
        result = ctx.runner.send(ctx.plan_chat(persona, query["prompt"], f"router:{query['id']}",
                                               query_cid))
        if result is None:
            continue
        record = (ctx.harvester.chat_record(query_cid, result.started, result.ended, role)
                  if result.ok else None)
        if result.ok:
            latencies.append(result.elapsed_ms)
        rows.append({
            "id": query["id"],
            "telemetryJoin": (record or {}).get("_join"),
            "class": query.get("class", "unclassified"),
            "expectTier": query.get("expect_tier"),
            "httpStatus": result.status or result.error,
            "tier": tel_tier(record),
            "intentType": ((record or {}).get("routing") or {}).get("intentType"),
            "riskLevel": ((record or {}).get("routing") or {}).get("riskLevel"),
            "complexity": ((record or {}).get("routing") or {}).get("complexity"),
            "tierModel": ((record or {}).get("model") or {}).get("tierModel"),
            "routerModel": ((record or {}).get("model") or {}).get("routerModel"),
            "fallbackUsed": tel_fallback(record),
            "outcome": tel_status(record),
            "unsupportedAnswerFlag": tel_unsupported(record),
            "wallLatencyMs": round(result.elapsed_ms, 1),
            "telemetryTotalMs": tel_total_ms(record),
        })

    observed = [row for row in rows if row["tier"]]
    if not observed:
        suite.skip("router-telemetry", "Model tier + actual model name in telemetry",
                   "no nlti.request.telemetry record carried routing.tier")
        suite.observations["rows"] = rows
        return suite

    # Aggregate checks inherit the weakest join: one fallback-joined record poisons attribution
    # for the whole aggregate, so those checks are downgraded to UNVERIFIED-ATTRIBUTION.
    fallback_ids = sorted(r["id"] for r in observed if r["telemetryJoin"] == "window-fallback")
    joined = {"_join": "window-fallback"} if fallback_ids else {"_join": "correlationId"}
    suite.expect_joined("router-telemetry", "Model tier + actual model name in telemetry",
                        all(row["tier"] for row in observed),
                        f"{len(observed)}/{len(rows)} probes reported routing.tier; "
                        f"tierModel values={sorted({row['tierModel'] for row in observed})}",
                        joined)

    simple = [row for row in observed if row["class"] == "simple"]
    small_tiers = {TIER_SIMPLE, TIER_RULE}
    simple_small = [row for row in simple if row["tier"] in small_tiers]
    simple_pct = (100.0 * len(simple_small) / len(simple)) if simple else None
    if simple:
        simple_tiers = {row["id"]: row["tier"] for row in simple}
        suite.expect_joined("router-simple-mix",
                            "≥80% simple fixtures routed to T2-simple (or the T0 rule)",
                            simple_pct is not None and simple_pct >= ctx.args.simple_tier_floor,
                            f"%={simple_pct:.1f} floor={ctx.args.simple_tier_floor:.0f} "
                            f"({len(simple_small)}/{len(simple)}); tiers={simple_tiers}", joined)
    else:
        suite.skip("router-simple-mix", "≥80% simple fixtures routed to T2-simple", "no simple probes")

    writes = [row for row in observed if row["class"] == "write"]
    if writes:
        write_tiers = {row["id"]: row["tier"] for row in writes}
        suite.expect_joined("router-write-complex", "All write fixtures routed to T2-complex",
                            all(row["tier"] == TIER_COMPLEX for row in writes),
                            f"tiers={write_tiers}", joined)
    else:
        suite.skip("router-write-complex", "All write fixtures routed to T2-complex",
                   "no write-class probes")

    sensitive = [row for row in observed if row["class"] == "sensitive"]
    if sensitive:
        sensitive_tiers = {row["id"]: row["tier"] for row in sensitive}
        suite.expect_joined("router-sensitive-complex",
                            "All accounting/tax/admin/security fixtures routed to T2-complex",
                            all(row["tier"] == TIER_COMPLEX for row in sensitive),
                            f"tiers={sensitive_tiers}", joined)
    else:
        suite.skip("router-sensitive-complex",
                   "All accounting/tax/admin/security fixtures routed to T2-complex",
                   "no sensitive-class probes")

    outcomes = {row["id"]: row["outcome"] for row in observed}
    suite.expect_joined("router-no-break", "Router output never breaks processing",
                        all(row["outcome"] in (None, "SUCCESS") for row in observed),
                        f"outcomes={outcomes}", joined)
    reported = sum(1 for row in observed if row["fallbackUsed"] is not None)
    fell_back = sorted(row["id"] for row in observed if row["fallbackUsed"])
    suite.expect_joined("router-fallback-visible", "Fallback usage visible in telemetry",
                        all(row["fallbackUsed"] is not None for row in observed),
                        f"model.fallbackUsed reported for {reported}/{len(observed)} probes; "
                        f"used on {fell_back}", joined)

    p95 = percentile(latencies, 95)
    suite.expect("router-p95", "p95 latency within the soft SLO",
                 p95 is not None and p95 <= ctx.args.p95_slo_ms,
                 f"p95={p95:.0f} ms soft SLO={ctx.args.p95_slo_ms} ms over {len(latencies)} probes"
                 if p95 is not None else "no successful probes")

    expected_hits = [row for row in observed if row["expectTier"]]
    agreed = [row for row in expected_hits if row["tier"] == row["expectTier"]]
    unsupported_count = sum(1 for row in observed if row["unsupportedAnswerFlag"])

    suite.metrics = [
        Metric("Simple probes on a small tier", None if simple_pct is None else round(simple_pct, 1), "%"),
        Metric("Tier agreement with the expected tier",
               None if not expected_hits else round(100.0 * len(agreed) / len(expected_hits), 1), "%"),
        Metric("Fallback usage", sum(1 for row in observed if row["fallbackUsed"]), "probes"),
        Metric("Answers flagged unsupported", unsupported_count, "probes"),
        Metric("p50 latency", percentile(latencies, 50), "ms"),
        Metric("p95 latency", p95, "ms"),
    ]
    suite.observations["rows"] = rows
    suite.observations["routingMix"] = _mix(observed)
    suite.notes.append(
        "Quality delta versus the Gate 0 baseline is not computed here — pass --baseline with the "
        "previous run's JSON to fill the Baseline/Δ columns, and diff answer quality with "
        "scripts/eval_live.py output as the plan's Wave 2 step requires.")
    return suite


def _mix(rows):
    mix = {}
    for row in rows:
        mix[row["tier"]] = mix.get(row["tier"], 0) + 1
    return mix


def plan_write_gate(ctx):
    persona = ctx.personas[0]
    prompt = ctx.args.write_gate_action_prompt or ctx.queries["write-gate"][0]["prompt"]
    client_context = None
    if ctx.args.write_target:
        target = load_write_target(ctx.args.write_target)
        prompt = target.get("prompt", prompt)
        client_context = target.get("clientContext")
    correlation = "<generated-uuid>"
    intent_gap = ctx.plan_nlti_submit(persona, WRITE_GATE_INTENT_GAP_PROMPT, "<generated-uuid>")
    intent_gap.label = "wg-intent-gap"
    intent_gap.note = ("wg-intent-gap probe — create-style prompt; documents on every run that "
                       "IntentParserServiceImpl:40 never classifies create/update phrasings as "
                       "ACTION (#1218 product gap)")
    plans = [
        ctx.plan_login(persona),
        ctx.plan_nlti_submit(persona, prompt, correlation, client_context=client_context),
        ctx.plan_confirm(persona, "<requestId from the submit response>", "<plan idempotencyKey>"),
        ctx.plan_confirm(persona, "<requestId>", "<plan idempotencyKey>"),
        ctx.plan_audit(persona, correlation),
        ctx.plan_cancel(persona, "<requestId>"),
        intent_gap,
    ]
    if len(ctx.personas) > 1:
        other = ctx.personas[1]
        plans.append(ctx.plan_login(other))
        under = ctx.plan_nlti_submit(other, prompt, "<generated-uuid>",
                                     client_context=client_context)
        under.note = "fail-closed probe: persona without the target tool permission"
        plans.append(under)
    return plans


def run_write_gate(ctx):
    gate, issue, title = SUITES["write-gate"]
    suite = SuiteResult("write-gate", gate, issue, title)
    persona = ctx.personas[0]
    ctx.authenticate(persona)

    target = load_write_target(ctx.args.write_target) if ctx.args.write_target else None
    if target is None:
        suite.notes.append(
            "OPEN DECISION C: no --write-target supplied, so the executing half of the flow is "
            "SKIPPED. The preview/fail-closed half below is read-safe and still meaningful.")
    prompt = ((target or {}).get("prompt") or ctx.args.write_gate_action_prompt
              or ctx.queries["write-gate"][0]["prompt"])
    client_context = (target or {}).get("clientContext")

    correlation = uuid7ish()
    submit = ctx.runner.send(ctx.plan_nlti_submit(persona, prompt, correlation,
                                                  client_context=client_context))
    if submit is None or not isinstance(submit.json_body, dict):
        suite.add("wg-preview", "ACTION prompt yields a write-plan preview", FAIL,
                  f"POST /v1/nlt/requests returned {getattr(submit, 'status', 'refused')}")
        return suite

    body = submit.json_body
    request_id = body.get("requestId")
    result_payload = body.get("result") if isinstance(body.get("result"), dict) else {}
    plan_payload = result_payload if result_payload.get("planId") else (body.get("writePlan") or {})
    suite.expect("wg-preview", "ACTION prompt yields a write-plan preview",
                 bool(plan_payload.get("planId")),
                 f"HTTP {submit.status}; status={body.get('status')}; "
                 f"planId={plan_payload.get('planId')} targetTool={plan_payload.get('targetTool')}")
    suite.expect("wg-not-executed", "Preview is never executed directly",
                 plan_payload.get("status") in (None, "PENDING_CONFIRMATION"),
                 f"plan.status={plan_payload.get('status')} (must be PENDING_CONFIRMATION)")
    suite.expect("wg-disclosure", "Inferred default args disclosed with the preview",
                 "inferredDefaultArgs" in plan_payload,
                 f"inferredDefaultArgs={plan_payload.get('inferredDefaultArgs')}")
    suite.expect("wg-risk", "Risk level assessed on the plan",
                 bool(plan_payload.get("riskLevel")),
                 f"riskLevel={plan_payload.get('riskLevel')}")
    if target and target.get("expectedTool"):
        suite.expect("wg-target-tool", "Preview targets the configured write target",
                     plan_payload.get("targetTool") == target["expectedTool"],
                     f"targetTool={plan_payload.get('targetTool')} "
                     f"expected={target['expectedTool']}")

    telemetry = ctx.harvester.by_correlation(correlation, submit.started, submit.ended)
    if telemetry is None:
        suite.skip("wg-telemetry", "Write signal present in telemetry",
                   "no nlti.request.telemetry record carried this correlationId — the NLTI submit "
                   "path does not emit telemetry today (only the two chat managers do)")
    else:
        suite.expect("wg-telemetry", "Write signal present in telemetry",
                     tel_is_write(telemetry) is True,
                     f"write.isWrite={tel_is_write(telemetry)}")

    idempotency_key = plan_payload.get("idempotencyKey")
    if not ctx.args.allow_writes or not target:
        reason = ("--allow-writes not passed" if not ctx.args.allow_writes
                  else "no --write-target (OPEN DECISION C)")
        for check_id, label in (
            ("wg-confirm", "Confirmation executes the persisted plan args"),
            ("wg-idempotent", "Re-confirming replays without double-executing"),
            ("wg-audit-execution", "CONFIRMATION + EXECUTION audit entries appended"),
        ):
            suite.skip(check_id, label, reason)
    elif not request_id:
        for check_id, label in (
            ("wg-confirm", "Confirmation executes the persisted plan args"),
            ("wg-idempotent", "Re-confirming replays without double-executing"),
            ("wg-audit-execution", "CONFIRMATION + EXECUTION audit entries appended"),
        ):
            suite.skip(check_id, label, "submit response carried no requestId")
    else:
        confirm = ctx.runner.send(ctx.plan_confirm(persona, request_id, idempotency_key))
        confirmed = (confirm.json_body or {}) if confirm else {}
        suite.expect("wg-confirm", "Confirmation executes the persisted plan args",
                     confirm is not None and confirm.ok,
                     f"HTTP {getattr(confirm, 'status', 'refused')} status={confirmed.get('status')} "
                     f"targetTool={confirmed.get('targetTool')}")
        replay = ctx.runner.send(ctx.plan_confirm(persona, request_id, idempotency_key))
        replayed = (replay.json_body or {}) if replay else {}
        suite.expect("wg-idempotent", "Re-confirming replays without double-executing",
                     replay is not None and replay.ok
                     and replayed.get("status") == confirmed.get("status"),
                     f"first={confirmed.get('status')} replay={replayed.get('status')} "
                     f"HTTP {getattr(replay, 'status', 'refused')}")
        audit = ctx.runner.send(ctx.plan_audit(persona, correlation))
        entries = ((audit.json_body or {}).get("content") or []) if audit and audit.ok else []
        types = sorted({entry.get("eventType") for entry in entries})
        suite.expect("wg-audit-execution", "CONFIRMATION + EXECUTION audit entries appended",
                     "CONFIRMATION" in types
                     and any(str(t).startswith("EXECUTION") for t in types),
                     f"audit eventTypes={types} for correlationId={correlation}")
        if target.get("cleanupNote"):
            suite.notes.append(f"Write target cleanup: {target['cleanupNote']}")

    cancel_id = request_id if not ctx.args.allow_writes else None
    if cancel_id:
        cancel = ctx.runner.send(ctx.plan_cancel(persona, cancel_id))
        if cancel is None:
            suite.skip("wg-cancel", "Preview can be cancelled and cancellation is idempotent",
                       "request refused by the safety gate")
        else:
            again = ctx.runner.send(ctx.plan_cancel(persona, cancel_id))
            suite.expect("wg-cancel", "Preview can be cancelled and cancellation is idempotent",
                         cancel.ok and again is not None and again.ok,
                         f"first HTTP {cancel.status or cancel.error}, "
                         f"repeat HTTP {getattr(again, 'status', 'refused')}; "
                         f"status={(cancel.json_body or {}).get('status')}")
    else:
        suite.skip("wg-cancel", "Preview can be cancelled and cancellation is idempotent",
                   "plan was confirmed in this run; cancelling a COMPLETE plan is a 409 by design")

    # 2026-08-19 live-run defect 3 / #1218 sign-off evidence: create/update prompts can never
    # reach the write gate. IntentParserServiceImpl:40 (HIGH_RISK_VERBS = {"delete", "remove"})
    # classifies ACTION only when risk is HIGH ("delete"/"remove" anywhere in the prompt or a
    # "bulk " prefix); create-style prompts parse as UNKNOWN -> plain ACCEPTED envelope, no write
    # plan. This check ALWAYS runs and is EXPECTED to FAIL until the parser gains create/update
    # coverage — do not silence it by changing the probe prompt.
    gap_correlation = uuid7ish()
    gap_plan = ctx.plan_nlti_submit(persona, WRITE_GATE_INTENT_GAP_PROMPT, gap_correlation)
    gap_plan.label = "wg-intent-gap"
    gap_plan.note = ("wg-intent-gap probe — create-style prompt; documents the #1218 "
                     "intent-parser gap")
    gap = ctx.runner.send(gap_plan)
    gap_label = "Create-style prompts can reach the write gate (#1218 intent-parser gap)"
    if gap is None:
        suite.skip("wg-intent-gap", gap_label, "request refused by the safety gate")
    elif not isinstance(gap.json_body, dict):
        suite.add("wg-intent-gap", gap_label, FAIL,
                  f"POST /v1/nlt/requests returned HTTP {gap.status or gap.error} with no JSON "
                  "body for the create-style probe")
    else:
        gap_body = gap.json_body
        gap_result = gap_body.get("result") if isinstance(gap_body.get("result"), dict) else {}
        gap_payload = (gap_result if gap_result.get("planId")
                       else (gap_body.get("writePlan") or {}))
        suite.expect(
            "wg-intent-gap", gap_label,
            bool(gap_payload.get("planId")),
            f"'{WRITE_GATE_INTENT_GAP_PROMPT}' -> HTTP {gap.status} "
            f"status={gap_body.get('status')} planId={gap_payload.get('planId')} — "
            "IntentParserServiceImpl:40 classifies ACTION only for HIGH risk (prompt contains "
            "'delete'/'remove' or starts with 'bulk '), so create/update prompts return the "
            "plain ACCEPTED envelope and can never produce a write-plan preview "
            "(#1218 product gap)")
    suite.notes.append(
        "wg-intent-gap is EXPECTED to FAIL until IntentParserServiceImpl classifies create/update "
        "phrasings as ACTION; it documents the #1218 product gap on every run and feeds the gate "
        "sign-off discussion. The wg-action probe uses a delete-verb prompt "
        "(--write-gate-action-prompt) because that is the only phrasing the parser routes to the "
        "write gate today.")

    if len(ctx.personas) > 1:
        other = ctx.personas[1]
        try:
            ctx.authenticate(other)
        except (ConfigError, InfraError) as exc:
            suite.skip("wg-fail-closed", "Caller without the tool permission is failed closed",
                       str(exc))
        else:
            probe_correlation = uuid7ish()
            probe_plan = ctx.plan_nlti_submit(other, prompt, probe_correlation,
                                              client_context=client_context)
            probe_plan.note = "fail-closed probe: persona without the target tool permission"
            probe = ctx.runner.send(probe_plan)
            if probe is None:
                suite.skip("wg-fail-closed", "Caller without the tool permission is failed closed",
                           "request refused by the safety gate")
            else:
                probe_body = probe.json_body if isinstance(probe.json_body, dict) else {}
                raw_result = probe_body.get("result")
                probe_result = raw_result if isinstance(raw_result, dict) else {}
                failed_closed = (probe.status in (401, 403)
                                 or not probe_result.get("planId"))
                suite.expect("wg-fail-closed",
                             "Caller without the tool permission is failed closed",
                             failed_closed,
                             f"persona '{other.name}' -> HTTP {probe.status or probe.error}, "
                             f"planId={probe_result.get('planId')} (no executable plan expected)")
    else:
        suite.skip("wg-fail-closed", "Caller without the tool permission is failed closed",
                   "needs a second persona that lacks the target tool permission")

    counts = suite.counts
    executed = counts[PASS] + counts[FAIL]
    suite.metrics = [
        Metric("Write-safety checks executed", executed, "checks"),
        Metric("Write-safety pass rate",
               None if not executed else round(100.0 * counts[PASS] / executed, 1), "%"),
        Metric("Write target", (target or {}).get("expectedTool") or "NOT SET (decision C)"),
    ]
    return suite


def plan_admin(ctx):
    admins = [p for p in ctx.personas if p.admin] or ctx.personas[:1]
    persona = admins[0]
    plans = [
        ctx.plan_login(persona),
        ctx.plan_get(persona, "prompts", "admin-list-prompts",
                     "SystemPromptController#list — requires mcp:system_prompt:view"),
        ctx.plan_get(persona, "llm-apis", "admin-list-llm-apis",
                     "LlmApiConfigController#list — requires mcp:llm_api:view"),
        ctx.plan_get(persona, f"tools/{ctx.args.admin_tool_name}/permissions", "admin-tool-perms",
                     "ToolPermissionController#list — requires mcp:tool:view"),
        ctx.plan_audit(persona),
    ]
    non_admin = next((p for p in ctx.personas if not p.admin and p.name != persona.name), None)
    if non_admin:
        plans.append(ctx.plan_login(non_admin))
        probe = ctx.plan_get(non_admin, "prompts", "admin-fail-closed",
                             "fail-closed probe — expects HTTP 403 without mcp:system_prompt:view")
        plans.append(probe)
    plans.append(RequestPlan(
        label="loki-tuning-shadow",
        method="GET",
        url=f"{ctx.args.loki_url.rstrip('/')}/loki/api/v1/query_range?query="
            f'{{{ctx.args.loki_label}="{ctx.args.loki_service}"}} |= "mcp.tuning.shadow"',
        impact=READ,
        note=f"shadow->live tuning evidence over the last {ctx.args.tuning_window_hours}h "
             "(ToolPriorityTuningService SHADOW_LOGGER)",
    ))
    return plans


def run_admin(ctx):
    gate, issue, title = SUITES["admin"]
    suite = SuiteResult("admin", gate, issue, title)
    admins = [p for p in ctx.personas if p.admin] or ctx.personas[:1]
    persona = admins[0]
    ctx.authenticate(persona)

    reads = [
        ("admin-prompts", "System-prompt admin surface reachable", "prompts",
         "admin-list-prompts", "SystemPromptController#list — requires mcp:system_prompt:view"),
        ("admin-llm-apis", "LLM API config admin surface reachable", "llm-apis",
         "admin-list-llm-apis", "LlmApiConfigController#list — requires mcp:llm_api:view"),
        ("admin-tool-perms", "Tool permission admin surface reachable",
         f"tools/{ctx.args.admin_tool_name}/permissions", "admin-tool-perms",
         "ToolPermissionController#list — requires mcp:tool:view"),
    ]
    for check_id, label, path, plan_label, note in reads:
        result = ctx.runner.send(ctx.plan_get(persona, path, plan_label, note))
        if result is None:
            suite.skip(check_id, label, "request refused by the safety gate")
            continue
        suite.expect(check_id, label, result.ok, f"GET /{path} -> HTTP {result.status or result.error}")

    audit = ctx.runner.send(ctx.plan_audit(persona))
    if audit is None:
        suite.skip("admin-audit", "Audit ledger queryable by an admin persona",
                   "request refused by the safety gate")
    else:
        suite.expect("admin-audit", "Audit ledger queryable by an admin persona", audit.ok,
                     f"GET /nlt/audit -> HTTP {audit.status or audit.error}")

    non_admin = next((p for p in ctx.personas if not p.admin and p.name != persona.name), None)
    if non_admin:
        try:
            ctx.authenticate(non_admin)
        except (ConfigError, InfraError) as exc:
            suite.skip("admin-fail-closed", "Admin surface is permission fail-closed", str(exc))
        else:
            probe = ctx.plan_get(non_admin, "prompts", "admin-fail-closed",
                                 "fail-closed probe — expects HTTP 403")
            result = ctx.runner.send(probe)
            if result is None:
                suite.skip("admin-fail-closed", "Admin surface is permission fail-closed",
                           "request refused by the safety gate")
            else:
                suite.expect("admin-fail-closed", "Admin surface is permission fail-closed",
                             result.status in (401, 403),
                             f"persona '{non_admin.name}' GET /prompts -> "
                             f"HTTP {result.status or result.error} (expected 401/403)")
    else:
        suite.skip("admin-fail-closed", "Admin surface is permission fail-closed",
                   "no non-admin persona configured")

    # shadow -> live tuning: read-only harvest of the ToolPriorityTuningService loggers.
    window_start = now_utc() - _seconds(ctx.args.tuning_window_hours * 3600)
    try:
        shadow_lines = ctx.harvester.loki.lines("mcp.tuning.shadow", window_start, now_utc())
        proposal_lines = ctx.harvester.loki.lines("proposed_priority", window_start, now_utc())
        live_lines = ctx.harvester.loki.lines("mode=live, eval gate passed", window_start, now_utc())
        downgrade_lines = ctx.harvester.loki.lines("Live tuning downgraded to shadow",
                                                   window_start, now_utc())
    except LokiError as exc:
        suite.skip("admin-tuning-shadow", "Shadow tuning proposals observed", str(exc))
        suite.skip("admin-tuning-gate", "Live promotion is gated on a fresh passing eval", str(exc))
    else:
        proposals = len(proposal_lines) or len(shadow_lines)
        suite.expect("admin-tuning-shadow", "Shadow tuning proposals observed", proposals > 0,
                     f"{proposals} proposal line(s) on logger mcp.tuning.shadow in the last "
                     f"{ctx.args.tuning_window_hours}h (set MCP_TUNING_MODE=shadow and wait for the "
                     "02:00 cron if this is 0)")
        suite.expect("admin-tuning-gate", "Live promotion is gated on a fresh passing eval",
                     bool(live_lines) or bool(downgrade_lines),
                     f"{len(live_lines)} live-promotion line(s), {len(downgrade_lines)} "
                     "downgrade-to-shadow line(s) — either proves the eval gate ran")
        suite.observations["tuning"] = {
            "shadowLines": len(shadow_lines),
            "proposalLines": len(proposal_lines),
            "livePromotions": len(live_lines),
            "downgrades": len(downgrade_lines),
        }

    if ctx.args.allow_writes:
        suite.skip("admin-mutation", "Admin write flow (create/update/delete a system prompt)",
                   "not exercised: mutating admin CRUD needs a disposable prompt id agreed for "
                   "alpha; run it manually until that target is decided")
    else:
        suite.skip("admin-mutation", "Admin write flow (create/update/delete a system prompt)",
                   "ADMIN_WRITE requires --allow-writes")

    counts = suite.counts
    suite.metrics = [
        Metric("Admin surfaces reachable", counts[PASS], "checks"),
        Metric("Shadow proposals in window",
               suite.observations.get("tuning", {}).get("proposalLines"), "lines"),
        Metric("Tuning observation window", ctx.args.tuning_window_hours, "h"),
    ]
    return suite


SUITE_IMPL = {
    "equivalence": (plan_equivalence, run_equivalence),
    "persona": (plan_persona, run_persona),
    "workflow": (plan_workflow, run_workflow),
    "router": (plan_router, run_router),
    "write-gate": (plan_write_gate, run_write_gate),
    "admin": (plan_admin, run_admin),
}


# --- reporting -----------------------------------------------------------------------------------
def fmt_metric(metric):
    value = metric.current
    if value is None:
        return "—"
    if isinstance(value, float):
        return f"{value:,.1f}{(' ' + metric.unit) if metric.unit else ''}"
    if isinstance(value, int) and not isinstance(value, bool):
        return f"{value}{(' ' + metric.unit) if metric.unit else ''}"
    return str(value)


def fmt_delta(current, baseline):
    if current is None or baseline is None:
        return "—"
    if not isinstance(current, (int, float)) or not isinstance(baseline, (int, float)):
        return "—"
    delta = current - baseline
    return f"{delta:+,.1f}"


def build_json(args, personas, suites, runner, harvester, started):
    return {
        "schemaVersion": 1,
        "harness": "scripts/nlti_live_verify.py",
        "issue": "#1367",
        "generatedAt": iso(now_utc()),
        "startedAt": iso(started),
        "mode": "dry-run" if args.dry_run else ("read-only" if args.read_only
                                                else ("writes-allowed" if args.allow_writes
                                                      else "observe")),
        "gatewayUrl": args.gateway_url,
        "serviceRoute": args.service_route,
        "apiVersion": args.api_version,
        "lokiUrl": args.loki_url,
        "personas": [{"name": p.name, "expectedRole": p.expected_role, "admin": p.admin,
                      "description": p.description} for p in personas],
        "suites": [
            {
                "name": suite.name,
                "gate": suite.gate,
                "issue": suite.issue,
                "title": suite.title,
                "status": suite.status,
                "counts": suite.counts,
                "checks": [{"id": c.id, "label": c.label, "status": c.status,
                            "evidence": c.evidence} for c in suite.checks],
                "metrics": [{"name": m.name, "current": m.current, "unit": m.unit,
                             "verdict": m.verdict, "note": m.note} for m in suite.metrics],
                "observations": suite.observations,
                "notes": suite.notes,
            }
            for suite in suites
        ],
        "requests": runner.records,
        "safetySkips": runner.skipped_for_safety,
        "telemetryErrors": harvester.errors,
        "summary": {
            "suitesRun": len(suites),
            "passed": sum(1 for s in suites if s.status == PASS),
            "failed": sum(1 for s in suites if s.status == FAIL),
            "skipped": sum(1 for s in suites if s.status == SKIP),
            "unverifiedAttribution": sum(1 for s in suites if s.status == UNVERIFIED),
            "checksPassed": sum(s.counts[PASS] for s in suites),
            "checksFailed": sum(s.counts[FAIL] for s in suites),
            "checksSkipped": sum(s.counts[SKIP] for s in suites),
            "checksUnverifiedAttribution": sum(s.counts[UNVERIFIED] for s in suites),
        },
    }


def build_markdown(args, personas, suites, baseline, started):
    mode = "dry-run" if args.dry_run else ("read-only" if args.read_only
                                           else ("writes-allowed" if args.allow_writes else "observe"))
    persona_list = ", ".join(f"`{p.name}`" for p in personas) or "none"
    out = [
        "<!-- generated by scripts/nlti_live_verify.py (#1367) — paste each block into "
        "pos-mcp-server/docs/implementation_checklist.md -->",
        "",
        f"# NLTI live verification — {iso(started)}",
        "",
        f"- Harness: `scripts/nlti_live_verify.py` · mode `{mode}` · "
        f"gateway `{args.gateway_url}/{args.service_route}` (`X-API-Version: {args.api_version}`)",
        f"- Personas: {persona_list} · telemetry harvested from Loki `{args.loki_url}`",
        "",
    ]
    for suite in suites:
        counts = suite.counts
        decision = {PASS: "Pass", FAIL: "HOLD", SKIP: "HOLD", UNVERIFIED: "HOLD"}[suite.status]
        out += [
            f"### Gate {suite.gate} — {suite.title} ({suite.issue})",
            "",
            f"- Command: `scripts/nlti_live_verify.py --suite {suite.name}` · "
            f"run `{iso(started)}` · mode `{mode}`",
            f"- Result: **{suite.status}** — {counts[PASS]} passed, {counts[FAIL]} failed, "
            f"{counts[SKIP]} skipped, {counts[UNVERIFIED]} unverified-attribution",
            "",
            "#### Correctness tests",
            "",
        ]
        if not suite.checks:
            out.append("- (no checks executed)")
        for check in suite.checks:
            box = "x" if check.status == PASS else " "
            evidence = check.evidence or "no evidence captured"
            out.append(f"- [{box}] {check.label} — _ev: {evidence}_")
        out += ["", "#### Metrics", "",
                "| Metric | Baseline | Current | Δ | Pass/Fail |",
                "| ------ | -------: | ------: | -: | --------- |"]
        base_metrics = (baseline or {}).get(suite.name, {})
        for metric in suite.metrics:
            base_value = base_metrics.get(metric.name)
            base_metric = Metric(metric.name, base_value, metric.unit)
            out.append(
                f"| {metric.name} | {fmt_metric(base_metric)} | {fmt_metric(metric)} | "
                f"{fmt_delta(metric.current, base_value)} | {metric.verdict} |")
        if suite.notes:
            out += ["", "#### Notes", ""]
            out += [f"- {note}" for note in suite.notes]
        out += [
            "",
            "#### Sign-off record",
            "",
            "```",
            f"Gate:           {suite.gate}",
            f"Date:           {iso(started)[:10]}",
            "Executed by:    scripts/nlti_live_verify.py",
            f"Scope complete: {counts[PASS]}/{len(suite.checks)} checked",
            "Cross-locks:    <fill from the Cross-Phase Locks block>",
            f"Decision:       {decision}",
            f"Exceptions:     {counts[SKIP]} skipped, {counts[UNVERIFIED]} "
            "unverified-attribution check(s) — see evidence lines",
            "Rollback:       n/a (verification only)",
            "Next-gate appr: <approver / date / conditions>",
            "```",
            "",
            "---",
            "",
        ]
    return "\n".join(out)


def load_baseline(path):
    if not path:
        return {}
    file = Path(path)
    if not file.is_file():
        raise SystemExit(f"--baseline file not found: {file}")
    raw = json.loads(file.read_text())
    baseline = {}
    for suite in raw.get("suites", []):
        baseline[suite.get("name")] = {m.get("name"): m.get("current")
                                       for m in suite.get("metrics", [])}
    return baseline


# --- CLI -----------------------------------------------------------------------------------------
def parse_suites(values):
    if not values:
        return list(SUITES)
    names = []
    for value in values:
        for part in value.split(","):
            part = part.strip()
            if not part:
                continue
            if part == "all":
                names.extend(SUITES)
                continue
            if part not in SUITES:
                raise SystemExit(f"unknown suite '{part}'; choose from {', '.join(SUITES)}, all")
            names.append(part)
    ordered = [name for name in SUITES if name in names]
    return ordered or list(SUITES)


def build_parser():
    parser = argparse.ArgumentParser(
        prog="nlti_live_verify.py",
        description="Live HTTP verification harness for the NLTI gates (#1367).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Suites: " + ", ".join(f"{name} (Gate {gate}, {issue})"
                                      for name, (gate, issue, _) in SUITES.items()))
    parser.add_argument("--suite", action="append", default=[],
                        help="comma-separated suite list (repeatable); 'all' runs every suite")
    parser.add_argument("--gateway-url", default=os.environ.get("POS_GATEWAY_URL",
                                                                "http://localhost:8080"),
                        help="pos-api-gateway base URL (default http://localhost:8080)")
    parser.add_argument("--service-route", default="mcp-server",
                        help="gateway route segment for pos-mcp-server (default mcp-server)")
    parser.add_argument("--security-route", default="security-service",
                        help="gateway route segment for pos-security-service")
    parser.add_argument("--api-version", type=int, default=1,
                        help="value sent in the X-API-Version header (default 1)")
    parser.add_argument("--already-versioned", action="store_true",
                        help="emit /{route}/v{N}/... paths (gateway rewrite bypass / direct-to-service)")
    parser.add_argument("--personas", default=str(DEFAULT_PERSONAS),
                        help="persona config file (JSON always; YAML needs PyYAML)")
    parser.add_argument("--persona", action="append", default=[],
                        help="restrict to this persona name (repeatable)")
    parser.add_argument("--env-file", default=None,
                        help="fallback env file for persona secrets (default $ENV_FILE or ./.env)")
    parser.add_argument("--queries", default=None,
                        help="JSON file overriding the built-in probe prompts, keyed by suite")
    parser.add_argument("--loki-url", default=os.environ.get("LOKI_URL", "http://localhost:3100"),
                        help="Loki base URL (default http://localhost:3100)")
    parser.add_argument("--loki-label", default="service",
                        help="Loki stream label carrying the service name (promtail sets 'service')")
    parser.add_argument("--loki-service", default="pos-mcp-server",
                        help="value of the Loki service label for pos-mcp-server")
    parser.add_argument("--loki-selector", default=None,
                        help="raw LogQL stream selector, overriding --loki-label/--loki-service")
    parser.add_argument("--loki-tenant", default=None,
                        help="X-Scope-OrgID when Loki multi-tenancy is enabled (off by default)")
    parser.add_argument("--loki-limit", type=int, default=1000,
                        help="max log lines per Loki query (default 1000)")
    parser.add_argument("--telemetry-wait-seconds", type=float, default=20.0,
                        help="how long to poll Loki for a telemetry record (default 20)")
    parser.add_argument("--telemetry-poll-seconds", type=float, default=2.0,
                        help="delay between Loki polls (default 2)")
    parser.add_argument("--telemetry-window-pad-seconds", type=float, default=3.0,
                        help="clock-skew padding on the request window join (default 3)")
    parser.add_argument("--workflow-state", default="CREATING_PO", choices=WORKFLOW_STATES,
                        help="workflow state the workflow suite activates (default CREATING_PO)")
    parser.add_argument("--admin-tool-name", default="listInventoryItems",
                        help="discovered tool name used by the admin permission read probe")
    parser.add_argument("--tuning-window-hours", type=float, default=48.0,
                        help="lookback for shadow-tuning evidence in the admin suite (default 48)")
    parser.add_argument("--simple-tier-floor", type=float, default=80.0,
                        help="minimum %% of simple probes that must land on a small tier (default 80)")
    parser.add_argument("--p95-slo-ms", type=float, default=15000.0,
                        help="soft p95 latency SLO for the router suite in ms (default 15000)")
    parser.add_argument("--allow-writes", action="store_true",
                        help="permit BUSINESS_WRITE/ADMIN_WRITE requests; OFF by default so no "
                             "alpha data is mutated")
    parser.add_argument("--read-only", action="store_true",
                        help="stricter than the default: only READ requests execute")
    parser.add_argument("--write-gate-action-prompt", default=None,
                        help="ACTION probe prompt for the write-gate suite (default: "
                             f"'{DEFAULT_WRITE_GATE_ACTION_PROMPT}'). Must contain "
                             "'delete'/'remove' or start with 'bulk ' — "
                             "IntentParserServiceImpl classifies ACTION only for HIGH-risk "
                             "prompts, anything else never reaches the write gate. A "
                             "--write-target prompt takes precedence; a --queries override "
                             "applies only when this flag is not passed.")
    parser.add_argument("--write-target", default=None,
                        help="JSON file describing the #1218 write target (OPEN DECISION C — there "
                             "is deliberately no default). Required with --allow-writes for the "
                             "write-gate suite.")
    parser.add_argument("--capture-answers", action="store_true",
                        help="store full answer text in the JSON artefact (off by default)")
    parser.add_argument("--baseline", default=None,
                        help="prior run's JSON, used to fill the Baseline/Δ metric columns")
    parser.add_argument("--out", default=str(DEFAULT_OUT),
                        help=f"output directory (default {DEFAULT_OUT})")
    parser.add_argument("--json-out", default=None, help="override the JSON result path")
    parser.add_argument("--markdown-out", default=None, help="override the markdown evidence path")
    parser.add_argument("--timeout", type=int, default=180, help="per-request timeout in seconds")
    parser.add_argument("--dry-run", action="store_true",
                        help="print the exact request plan and exit 0 without any network call")
    parser.add_argument("--verbose", action="store_true", help="log every request as it is issued")
    return parser


def do_dry_run(ctx, suite_names):
    print("nlti_live_verify.py — DRY RUN (no network calls issued)")
    print(f"  gateway        : {ctx.args.gateway_url}/{ctx.args.service_route} "
          f"(X-API-Version: {ctx.args.api_version}"
          f"{', already-versioned paths' if ctx.args.already_versioned else ''})")
    selector = ctx.args.loki_selector or '{%s="%s"}' % (ctx.args.loki_label, ctx.args.loki_service)
    print(f"  loki           : {ctx.args.loki_url} selector {selector}")
    print(f"  personas       : {', '.join(p.name for p in ctx.personas)}")
    mode = "read-only" if ctx.args.read_only else ("writes-allowed" if ctx.args.allow_writes
                                                   else "observe (no business writes)")
    print(f"  safety mode    : {mode}")
    print(f"  write target   : {ctx.args.write_target or 'NOT SET — OPEN DECISION C (#1218)'}")
    total = 0
    for name in suite_names:
        gate, issue, title = SUITES[name]
        plan_fn, _ = SUITE_IMPL[name]
        plans = plan_fn(ctx)
        print("")
        print(f"=== suite {name} — Gate {gate} {issue}: {title} ({len(plans)} requests) ===")
        for index, plan in enumerate(plans, start=1):
            allowed, reason = ctx.runner.allowed(plan)
            marker = "SEND" if allowed else "SKIP"
            print(f"  [{index:>2}] {marker} {plan.describe()}")
            if not allowed:
                print(f"       reason     : {reason}")
        total += len(plans)
    print("")
    print(f"dry run complete: {total} planned requests across {len(suite_names)} suite(s); "
          "no sockets were opened.")
    return EXIT_OK


def main(argv=None):
    args = build_parser().parse_args(argv)
    suite_names = parse_suites(args.suite)

    if args.read_only and args.allow_writes:
        raise SystemExit("--read-only and --allow-writes are mutually exclusive")
    if "write-gate" in suite_names and args.allow_writes and not args.write_target:
        raise SystemExit(
            "--suite write-gate with --allow-writes requires --write-target <file.json>: the #1218 "
            "write target is an open decision (plan decision C) and is deliberately not defaulted.")

    if args.write_target:
        # Fail fast on a placeholder / malformed target before anything is printed or sent.
        load_write_target(args.write_target)
    personas = load_personas(args.personas, args.persona)
    queries = load_queries(args.queries)
    baseline = load_baseline(args.baseline)
    runner = Runner(args)
    harvester = TelemetryHarvester(LokiClient(args), args)
    ctx = Context(args, personas, queries, runner, harvester)

    if args.dry_run:
        return do_dry_run(ctx, suite_names)

    started = now_utc()
    suites = []
    infra_failure = None
    for name in suite_names:
        gate, issue, title = SUITES[name]
        _, run_fn = SUITE_IMPL[name]
        print(f"== suite {name} (Gate {gate}, {issue}) ==")
        try:
            suites.append(run_fn(ctx))
        except ConfigError as exc:
            suite = SuiteResult(name, gate, issue, title)
            suite.skip(f"{name}-config", title, str(exc))
            suites.append(suite)
        except InfraError as exc:
            suite = SuiteResult(name, gate, issue, title)
            suite.add(f"{name}-infra", title, FAIL, str(exc))
            suites.append(suite)
            infra_failure = str(exc)

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = started.strftime("%Y%m%dT%H%M%SZ")
    json_path = Path(args.json_out) if args.json_out else out_dir / f"nlti-live-verify-{stamp}.json"
    md_path = (Path(args.markdown_out) if args.markdown_out
               else out_dir / f"nlti-live-verify-{stamp}.md")
    payload = build_json(args, personas, suites, runner, harvester, started)
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(payload, indent=2, default=str) + "\n")
    md_path.parent.mkdir(parents=True, exist_ok=True)
    md_path.write_text(build_markdown(args, personas, suites, baseline, started) + "\n")

    summary = payload["summary"]
    print("")
    print(f"suites: {summary['suitesRun']} run — {summary['passed']} pass, "
          f"{summary['failed']} fail, {summary['skipped']} skipped, "
          f"{summary['unverifiedAttribution']} unverified-attribution")
    print(f"checks: {summary['checksPassed']} pass, {summary['checksFailed']} fail, "
          f"{summary['checksSkipped']} skipped, "
          f"{summary['checksUnverifiedAttribution']} unverified-attribution")
    if runner.skipped_for_safety:
        print(f"safety: {len(runner.skipped_for_safety)} request(s) not issued "
              "(pass --allow-writes to include them)")
    if harvester.errors:
        print(f"loki  : {len(harvester.errors)} query error(s) — telemetry checks were skipped")
    print(f"json  : {json_path}")
    print(f"md    : {md_path}")

    if infra_failure:
        return EXIT_INFRA
    return EXIT_CHECK_FAILED if summary["checksFailed"] else EXIT_OK


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit as exit_signal:
        if isinstance(exit_signal.code, str):
            print(exit_signal.code, file=sys.stderr)
            sys.exit(EXIT_CONFIG)
        raise
    except KeyboardInterrupt:
        sys.exit(EXIT_INFRA)
