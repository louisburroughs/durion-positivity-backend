# NLTI Dashboards

Summary: Grafana dashboards for the NL-interface. Two provisioned dashboards cover request health
and the Gate 7 verification surface (routing mix, model tier, fallback, prompt layers, permission
rejects, RAG recall, write confirmation).

## Provisioned dashboard files

| Dashboard | UID | JSON file | Primary datasource |
|---|---|---|---|
| NLTI Overview (pos-mcp-server) | `nlti-overview` | `observability/grafana/provisioning/dashboards/json/nlti-overview.json` | Prometheus, with Loki for percentiles/outcome splits |
| NLTI Gate 7 — Routing, Model, RAG and Write Safety | `nlti-gate7` | `observability/grafana/provisioning/dashboards/json/nlti-gate7.json` | Loki, with Prometheus for HTTP status and preload |

Both files are picked up by the file provider in
`observability/grafana/provisioning/dashboards/dashboards.yml` (folder `Durion POS`, 30 s refresh,
`allowUiUpdates: true`). Datasource UIDs are pinned to `prometheus` and `loki` by
`observability/grafana/provisioning/datasources/datasources.yml`.

## Datasources

Two distinct telemetry paths back these panels. Every panel below states which one it uses.

**Loki (LogQL) — `nlti.request.telemetry`.** `LoggingNltiTelemetryEmitter` writes one JSON line per
request to the dedicated `nlti.telemetry` logger. Promtail tails the container's Docker log and
labels it `{job="docker", service="pos-mcp-server", level="INFO"}`. The line is a normal Spring Boot
log line whose *message* is the JSON, so a bare `| json` cannot parse it — every telemetry panel
uses this prefix:

```logql
{job="docker", service="pos-mcp-server"}
  |= "nlti.request.telemetry"
  | regexp `(?P<telemetry>[{]"schemaVersion".+[}])`
  | line_format "{{.telemetry}}"
  | json | __error__=""
```

`| json` flattens nested objects with `_` (`routing.tier` → `routing_tier`) but **skips arrays**.
`rag.promptLayers`, `tools.selected` and `rag.retrieved` therefore need the parameterized form,
e.g. `| json prompt_layers="rag.promptLayers"`, which yields the array as a JSON-encoded string.

**Prometheus (PromQL) — `/actuator/prometheus`.** Scraped by the `pos-mcp-server` job added to
`observability/prometheus.yml` (target `pos-mcp-server:8086`, no basic auth — the service's
`SecurityConfiguration` only guards `/v1/mcp/**` and `/v1/nlt/**`). Micrometer name mapping:
`nlt.request.count` → `nlt_request_count_total`, `nlt.request.latency_ms` →
`nlt_request_latency_ms_seconds_{count,sum,max}`.

> **No percentile histograms.** `pos-mcp-server` does not import `application-observability.yml`, so
> `management.metrics.distribution.percentiles-histogram` is unset and **no** timer publishes
> `_bucket` series. `histogram_quantile(...)` returns nothing. Prometheus latency panels are means
> (`rate(_sum)/rate(_count)`); true percentiles come from Loki `quantile_over_time` over
> `latency.totalMs`.

---

## Dashboard 1 — NLTI Overview (`nlti-overview`)

Request health, latency, errors, and intent/clarification activity.

- Panel: NLTI Request Count
  - Metric: `nlt.request.count`, `nlt.error.count` (Prometheus)
  - Type: timeseries
  - Description: Submits and errors per minute on `POST /v1/nlt/requests`. Both counters are
    **untagged** — `NltiRequestServiceImpl` registers them with no tags, so the "split by status code
    and route" belongs to the next panel, not this one.

- Panel: HTTP requests by route and status
  - Metric: `http_server_requests_seconds_count{uri=~"/v1/(mcp|nlt)/.*"}` (Prometheus)
  - Type: timeseries
  - Description: The per-route, per-status split. `uri` is the Spring templated path, so
    `/v1/nlt/requests/{requestId}/confirm` stays a single series.

- Panel: Chat outcome mix (telemetry)
  - Metric: `outcome.status` from `nlti.request.telemetry` (Loki)
  - Type: timeseries
  - Description: SUCCESS/ERROR mix for `/v1/mcp/chat` and `/v1/mcp/chat/stream`. Those paths do
    **not** increment `nlt.request.count`, so this is the only volume signal for them.

- Panel: Chat end-to-end latency (p50/p95/p99)
  - Metric: `latency.totalMs` from `nlti.request.telemetry` (Loki, `quantile_over_time`)
  - Type: timeseries with p50/p95/p99
  - Description: True percentiles for the chat path. `latency.t0Ms`/`t1Ms`/`t2Ms` are declared in the
    v1 schema but `NltiRequestTelemetryFactory` always passes null, so per-stage percentiles are not
    available here.

- Panel: `/v1/nlt/requests` submit latency (mean)
  - Metric: `nlt.request.latency_ms` (Prometheus, `rate(_sum)/rate(_count)` plus `_max`)
  - Type: timeseries
  - Description: The NLTI submit timer. Note the name: `NltiRequestServiceImpl` records
    `nlt.request.latency_ms`, while the `nlt.request.latency` bean in
    `NltiObservabilityMetricsConfig` is registered but never sampled — do not query the latter.

- Panel: NLTI Planning Latency *(row: instrumentation pending)*
  - Metric: `nlt.planning.latency` (Prometheus)
  - Type: timeseries
  - Description: Registered as a bean but no code path records a sample; the series exists with
    `count 0`. Panel is kept, correct, and collapsed until the planning stage is timed.

- Panel: NLTI Execution Latency *(row: instrumentation pending)*
  - Metric: `nlt.execution.latency`, `mcp.model.latency`, `mcp.tool.execution.latency` (Prometheus)
  - Type: timeseries
  - Description: Same caveat — all three timers are registered-but-never-recorded today.

- Panel: NLTI Errors
  - Metric: `outcome.errorCode` from `nlti.request.telemetry` (Loki); `nlt.error.count` for the rate
  - Type: timeseries
  - Description: Error volume broken down by error code. The Micrometer counter carries no error-type
    tag, so the breakdown must come from the telemetry stream.

- Panel: Audit Write Failures
  - Metric: `nlt.audit.write_failures` (Prometheus)
  - Type: stat
  - Description: Incremented by `AuditLedgerServiceImpl` when the append-only ledger write throws.
    Any non-zero value is a P1 — destructive event types re-throw and block execution.

- Panel: Intent Parse Attempts / Intent Clarification Count
  - Metric: `nlt.intent.parse.count`, `nlt.intent.clarification.count` (Prometheus)
  - Type: timeseries (three series: parses/min, clarifications/min, clarification ratio)
  - Description: Both counters fire in `IntentParserServiceImpl`. The ratio is the intent-regression
    signal; the raw counts alone move with traffic.

- Panel: Raw `nlti.request.telemetry` stream
  - Metric: the Loki log stream itself
  - Type: logs
  - Description: Drill-down for spike windows. `correlationId` links each line to the audit ledger
    (`GET /v1/nlt/audit`) and to `scripts/nlti_live_verify.py` evidence output.

---

## Dashboard 2 — NLTI Gate 7 (`nlti-gate7`)

The seven panel areas Gate 7 (#1219) requires. Template variable `$role` is a **textbox** regex
matched against `actor.primaryRole`; it is not a query variable because `actor_primaryRole` is a
pipeline-parsed label that Loki's `label_values` API cannot enumerate.

### 1. Routing mix

- Panel: Routing mix by intent type
  - Metric: `routing.intentType` (Loki)
  - Type: timeseries
  - Description: The Gate 4 T1 classification. An empty-label series means no router decision was
    attached — Tier-0 simple chat, or tiering disabled.

- Panel: Routing mix by domain and risk level
  - Metric: `routing.domain`, `routing.riskLevel` (Loki)
  - Type: timeseries
  - Description: `riskLevel` drives write-gate policy: `WritePlanPolicy` applies stale-source-data
    protection only to risk >= MEDIUM.

- Panel: Workflow-state mix and Tier-0 rule hits
  - Metric: `routing.workflowState`, `routing.simpleChatRule` (Loki)
  - Type: timeseries
  - Description: Gate 2C workflow states (IDLE, CREATING_PO, RECEIVING_ASN, INVENTORY_RECON,
    PROCESSING_RETURN) and the named rule that short-circuited the router.

### 2. Model tier

- Panel: Requests by serving tier
  - Metric: `routing.tier` (Loki)
  - Type: timeseries
  - Description: `T0_RULE`, `T1_ROUTER`, `T2_SIMPLE`, `T2_COMPLEX` per
    `NltiRequestTelemetry.Tier`. This is the #1216 routing-mix evidence panel.

- Panel: Tier share (dashboard window)
  - Metric: `routing.tier` over `$__range` (Loki, instant)
  - Type: piechart
  - Description: Range-aggregated tier mix, formatted for the gate evidence table.

- Panel: Executing model by tier
  - Metric: `routing.tier` × `model.tierModel` (Loki)
  - Type: timeseries
  - Description: A non-empty tier with an empty `tierModel` means the tier resolved to the default
    chat model (`MCP_MODEL_SIMPLE` / `MCP_MODEL_COMPLEX` unset) — the Wave 2 pre-condition check.

### 3. Fallback rate

- Panel: Model fallback rate (`model.fallbackUsed`)
  - Metric: `model.fallbackUsed` (Loki)
  - Type: stat, percentunit
  - Description: **Gap.** `NltiRequestTelemetryFactory` hardcodes `fallbackUsed=false`, and the
    `fallbackChatModel` bean in `ModelFallbackConfiguration` is never injected into a call path, so
    this reads 0 by construction. The expression is correct against the v1 schema and will populate
    once failover is wired.

- Panel: Tier model-resolution fallbacks (WARN)
  - Metric: log filter `MCP tiered model resolver` at `level="WARN"` (Loki)
  - Type: timeseries
  - Description: The real fallback signal available today. `TieredChatModelResolver` warns when a
    tier cannot apply its model override and silently serves the default model. Non-zero here
    invalidates any tier-vs-tier quality comparison.

- Panel: Requests served with no tier decision
  - Metric: `routing.tier` present vs absent (Loki)
  - Type: timeseries
  - Description: Proxy for "the tiered router did not run". Expected near zero with
    `MCP_MODEL_TIERING_ENABLED=true` — and expected at **100 %** today, because tiering is dormant
    (#1683): both tier models were blank, so T2-simple and T2-complex resolved to the same
    `gpt-oss:120b` and the per-turn `qwen3:4b` classification bought nothing. Read this panel, the
    tier-mix panels above, and the `NltiModelTierStarved` alert as "tiering is off on purpose"
    until `MCP_MODEL_SIMPLE` names a real smaller model. See docs/gate4-tiered-router-design.md.

### 4. Prompt layers

- Panel: Prompt-layer composition rate by layer
  - Metric: `rag.promptLayers` (Loki, `| json prompt_layers="rag.promptLayers"`)
  - Type: timeseries, one series per layer
  - Description: BASE / ROLE / DOMAIN / TOOL_USE / WRITE_GATE per
    `NltiRequestTelemetry.PromptLayer`. Emitted by both `SessionAgentManager` and
    `StreamingSessionAgentManager`. This is the Gate 1 (#1213) evidence panel. Because the field is a
    JSON array, each series filters the encoded string on a quoted token (`` `"WRITE_GATE"` ``).

- Panel: Requests composed with no prompt layers
  - Metric: `rag.promptLayers` empty (Loki); `MCP no master prompt seeded` / `MCP no role persona
    seeded` WARN lines
  - Type: timeseries
  - Description: The factory omits the whole `rag` object when `RolePromptResolver.assemble()`
    returned no layers. Sustained non-zero means prompt seeding is broken for some role.

### 5. Permission rejects

- Panel: 403 responses on NLTI / MCP routes
  - Metric: `http_server_requests_seconds_count{status="403"|"401"}` by `uri` (Prometheus)
  - Type: timeseries
  - Description: The real permission-reject signal today. Covers
    `SessionOwnershipViolationException` and the write-gate dual permission check
    (`NltiWritePlanService.requireToolPermission` → `AccessDeniedException`).

- Panel: Caller permission-code count (p05 / p50)
  - Metric: `actor.permissionCodeCount` (Loki, `quantile_over_time ... unwrap`)
  - Type: timeseries
  - Description: A p05 collapsing toward 0 means the gateway bitset decode or the role-default
    permission fetch is failing — which surfaces downstream as tool-selection and RAG-visibility
    rejects rather than as an HTTP error.

- Panel: Tool candidates rejected for permissions
  - Metric: `tools.rejectedPermissionCount`, `tools.candidateCount` (Loki)
  - Type: timeseries
  - Description: **Gap.** `NltiRequestTelemetryFactory` hardcodes `rejectedPermissionCount=0`, and
    `PermissionAwareMetadataFilter` logs its drop count only at DEBUG (deployed baseline is INFO per
    ADR-0046), so no reject count is observable today. `candidateCount` is real.

### 6. RAG recall

- Panel: Top retrieved-document score (`rag.retrieved`)
  - Metric: `rag.retrieved[0].score` (Loki, indexed JSON expression)
  - Type: timeseries (p50 / p05)
  - Description: **Gap.** The factory always passes null for `Rag.retrieved` — Gate 5 has not wired
    retrieval scores into the emitter — so this panel has no data today. The array form requires the
    parameterized `| json` because a bare `| json` skips arrays.

- Panel: DOMAIN prompt-layer rate
  - Metric: `rag.promptLayers` containing `DOMAIN` ÷ total telemetry lines (Loki)
  - Type: timeseries, percentunit
  - Description: The interim recall signal that *is* emitted. `RolePromptResolver` adds the DOMAIN
    layer only when the selected tools resolved a rag-scope, so its share tracks whether requests are
    being grounded at all. Corpus-level recall evidence stays with `scripts/rag_lock_sweep.py`
    (Wave 0.2 / #1217).

- Panel: Static RAG corpus preload health
  - Metric: `mcp.rag.preload.duration` count (Prometheus) + `Preload failed for document_id=` WARN
    lines (Loki); mixed datasource
  - Type: timeseries
  - Description: Corpus availability underpins recall. Any preload failure means the 39-doc corpus is
    incomplete and Gate 5 recall numbers are not comparable across runs.

### 7. Write confirmation

- Panel: Write-plan confirm / cancel outcomes by HTTP status
  - Metric: `http_server_requests_seconds_count{uri=~"/v1/nlt/requests/{requestId}/(confirm|cancel)"}`
    (Prometheus)
  - Type: timeseries
  - Description: The authoritative write-gate outcome signal available today. `NltiExceptionHandler`
    maps each failure mode to a distinct status: 404 `WRITE_PLAN_NOT_FOUND`, 409
    `WRITE_PLAN_CONFLICT`/`WRITE_PLAN_STALE`, 410 `WRITE_PLAN_EXPIRED`, 403 permission re-check
    failure, 5xx `WRITE_PLAN_EXECUTION_FAILED`.

- Panel: Write-capable requests and plans created
  - Metric: `write.isWrite` (Loki) + `NLTI write plan created` INFO lines (Loki)
  - Type: timeseries
  - Description: `write.isWrite` is emitted (as `true`) only when the request's candidate tools
    included a write-capable tool — the Gate 6 preview trigger. A widening gap between the two series
    means write intents are detected but not turned into plans.

- Panel: Confirmation outcome mix (`write.confirmationOutcome`)
  - Metric: `write.confirmationOutcome` (Loki)
  - Type: timeseries
  - Description: **Gap.** The factory always passes null for `confirmationOutcome` and
    `planArgsProvenance`. The confirm/cancel path records outcomes
    (`confirmed`/`cancelled`/`expired`/`stale-data`/`superseded`) in the `nlti_audit_event` ledger —
    a database table with no log or metric mirror. Until that is wired, read confirmation outcomes
    from the HTTP-status panel or from `GET /v1/nlt/audit`.

- Panel: Adaptive tuning proposals by mode
  - Metric: `mcp.tuning.proposals{mode}` (Prometheus)
  - Type: timeseries
  - Description: Supporting panel for the #1219 shadow → live tuning verification.
    `ToolPriorityTuningService` tags each proposal with `mode` (`shadow`/`live`); a `live` proposal
    writes `mcp_tool.priority`.

---

## Known telemetry gaps (blocking full Gate 7 coverage)

| Field | Declared in | Emitted today | Consequence |
|---|---|---|---|
| `model.fallbackUsed` | `NltiRequestTelemetry.Model` | Hardcoded `false`; `fallbackChatModel` bean unused | No true fallback-rate signal; use the tier-resolver WARN panel |
| `tools.rejectedPermissionCount` | `NltiRequestTelemetry.Tools` | Hardcoded `0` | No per-request permission-reject count; use 403 rates |
| `rag.retrieved` | `NltiRequestTelemetry.Rag` | Always `null` | No per-request RAG recall/score; use DOMAIN-layer share + `rag_lock_sweep.py` |
| `write.confirmationOutcome`, `write.planArgsProvenance` | `NltiRequestTelemetry.Write` | Always `null` | Confirmation outcomes only via HTTP status or the audit ledger |
| `latency.t0Ms`, `t1Ms`, `t2Ms` | `NltiRequestTelemetry.Latency` | Always `null` | No per-stage latency; only `totalMs` |
| `quality.unsupportedAnswerFlag` | `NltiRequestTelemetry.Quality` | Whole `quality` object `null` | No answer-quality panel |
| `sessionId`, `requestId` | top level | Always `null` on the chat path | Correlate by `correlationId` only |
| `nlt.planning.latency`, `nlt.execution.latency`, `mcp.model.latency`, `mcp.tool.execution.latency` | `NltiObservabilityMetricsConfig` | Beans registered, never recorded | Stage-latency panels stay empty |

## Notes

- Use Loki `quantile_over_time` for latency percentiles; Prometheus cannot supply them on this
  service (no percentile histograms configured).
- Every Loki panel filters `service="pos-mcp-server"`, which Promtail derives from the Docker Compose
  service name — the label is identical on the local stack and on alpha.
- Drill down from any spike window via the raw telemetry logs panel on `nlti-overview`; the Loki
  datasource has a `TraceID` derived field that links a line to Jaeger.
