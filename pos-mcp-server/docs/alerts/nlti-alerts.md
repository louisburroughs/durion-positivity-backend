# NLTI Alert Rules

This file defines NLTI alert conditions and links to runbooks. It pairs with
`pos-mcp-server/docs/dashboards/nlti-overview.md`, which documents the two provisioned dashboards
(`nlti-overview`, `nlti-gate7`) and the datasource each signal comes from.

Two datasources back these alerts:

- **Prometheus** — Micrometer meters from `/actuator/prometheus`, scraped by the `pos-mcp-server` job
  in `observability/prometheus.yml`. Name mapping is the Micrometer default:
  `nlt.request.count` → `nlt_request_count_total`, `nlt.request.latency_ms` →
  `nlt_request_latency_ms_seconds_{count,sum,max}`.
- **Loki (LogQL)** — the `nlti.request.telemetry` JSON stream written by
  `LoggingNltiTelemetryEmitter`. The line is a Spring Boot log line whose message is the JSON, so
  every rule reuses this prefix (abbreviated `TELEMETRY` below):

  ```logql
  {job="docker", service="pos-mcp-server"} |= "nlti.request.telemetry"
    | regexp `(?P<telemetry>[{]"schemaVersion".+[}])` | line_format "{{.telemetry}}"
    | json | __error__=""
  ```

> **No percentile histograms.** `pos-mcp-server` does not import `application-observability.yml`, so
> no timer publishes `_bucket` series and `histogram_quantile(...)` is unusable. Latency alerts use
> Loki `quantile_over_time` over `latency.totalMs`, or a Prometheus mean
> (`rate(_sum)/rate(_count)`) where the chat telemetry does not apply.

## Request-health alerts

1. Name: HighNLTIErrorRate
   - Trigger: Prometheus — `sum(rate(nlt_error_count_total{service="pos-mcp-server"}[5m])) /
     clamp_min(sum(rate(nlt_request_count_total{service="pos-mcp-server"}[5m])), 0.0001) > 0.05`
     for 5 minutes. Both counters are untagged, so the error *type* breakdown comes from
     `outcome.errorCode` in the telemetry stream, not from the alert labels.
   - Severity: P1
   - Runbook: pos-mcp-server/docs/runbooks/authz-outage.md

2. Name: HighNLTIRequestLatency
   - Trigger: Loki — `quantile_over_time(0.99, TELEMETRY | unwrap latency_totalMs [5m]) > 2000`
     for 5 minutes (`latency.totalMs` is milliseconds). A Prometheus-only variant must use the mean,
     `rate(nlt_request_latency_ms_seconds_sum)/rate(nlt_request_latency_ms_seconds_count) > 2`
     (seconds), because no percentile buckets are published. Alert on
     `nlt.request.latency_ms`, never on `nlt.request.latency` — the latter is a registered bean that
     nothing samples.
   - Severity: P2
   - Runbook: pos-mcp-server/docs/runbooks/downstream-timeout.md

3. Name: AuditWriteFailuresDetected
   - Trigger: Prometheus — `sum(increase(nlt_audit_write_failures_total{service="pos-mcp-server"}[5m])) > 0`.
     `AuditLedgerServiceImpl` increments this whenever the append-only ledger write throws; for
     destructive event types the exception is re-thrown and blocks execution, so any hit is a P1.
   - Severity: P1
   - Runbook: pos-mcp-server/docs/runbooks/audit-storage-failure.md

4. Name: NLTIPlanningOrExecutionLatencyAnomaly
   - Trigger: Prometheus — mean over a 3 minute window on
     `nlt_planning_latency_seconds_{sum,count}` or `nlt_execution_latency_seconds_{sum,count}`
     exceeding 1 s. **Currently unactionable:** both timers (and `mcp.model.latency`,
     `mcp.tool.execution.latency`) are registered as beans in `NltiObservabilityMetricsConfig` but no
     code path records a sample, so the series exist with `count 0` and the rule can never fire.
     Keep it defined so it activates with the instrumentation; do not treat its silence as health.
   - Severity: P2
   - Runbook: pos-mcp-server/docs/runbooks/planning-failure.md

## Gate 7 alerts

One alert per Gate 7 panel area (`nlti-gate7` dashboard). Where the underlying telemetry field is not
populated yet, the rule states the substitute signal it fires on today.

5. Name: NltiRoutingMixShift
   - Trigger: Loki — the share of any one intent type moves by more than 30 percentage points against
     the previous day:
     `sum by (routing_intentType) (count_over_time(TELEMETRY [1h])) /
      sum(count_over_time(TELEMETRY [1h]))` compared to the same expression `offset 24h`, with a
     minimum volume guard of 100 requests in the current window. Also fires when
     `sum by (routing_intentType) (...)` is dominated by the empty label, which means the Gate 4
     router attached no classification at all.
   - Severity: P3 (investigate — a mix shift is a quality signal, not an outage)
   - Runbook: pos-mcp-server/docs/runbooks/planning-failure.md

6. Name: NltiModelTierStarved
   - Trigger: Loki — with `MCP_MODEL_TIERING_ENABLED=true`, either no `T2_COMPLEX` traffic at all
     over 1h while total traffic exceeds 100 requests
     (`sum(count_over_time(TELEMETRY | routing_tier = "T2_COMPLEX" [1h])) == 0`), or the
     no-tier share exceeds 20 %
     (`sum(count_over_time(TELEMETRY | routing_tier = "" [1h])) / sum(count_over_time(TELEMETRY [1h])) > 0.2`).
     Either means the tiered router is not routing and any tier-quality comparison collected in that
     window is void.
   - Severity: P2
   - Runbook: pos-mcp-server/docs/runbooks/planning-failure.md

7. Name: NltiModelFallbackRateHigh
   - Trigger: **Today** — Loki log rule on the tier resolver:
     `sum(count_over_time({job="docker", service="pos-mcp-server", level="WARN"}
      |= "MCP tiered model resolver" [10m])) > 0`, which means a tier silently served the default
     model. **Once fallback is instrumented** — Loki:
     `sum(count_over_time(TELEMETRY | model_fallbackUsed = "true" [10m])) /
      clamp_min(sum(count_over_time(TELEMETRY [10m])), 1) > 0.05`.
     The telemetry form cannot fire yet: `NltiRequestTelemetryFactory` hardcodes
     `Model.fallbackUsed=false` and the `fallbackChatModel` bean is never injected into a call path.
   - Severity: P2
   - Runbook: pos-mcp-server/docs/runbooks/downstream-timeout.md

8. Name: NltiPromptLayerMissing
   - Trigger: Loki — requests composed with no prompt layers over a 15 minute window:
     `sum(count_over_time({...} | json prompt_layers="rag.promptLayers" | prompt_layers = "" [15m])) > 0`,
     or the WARN stream
     `|~ "MCP no master prompt seeded|MCP no role persona seeded"`. Also alert when the `ROLE` layer
     share drops below 90 % of composed prompts — the role persona is what carries the
     permission-appropriate behaviour. `rag.promptLayers` is a JSON array, so the parameterized
     `| json` form is required; a bare `| json` skips arrays and silently yields no data.
   - Severity: P2 (prompts degrade to the built-in BASE layer — answers lose role and domain grounding)
   - Runbook: pos-mcp-server/docs/runbooks/planning-failure.md

9. Name: NltiPermissionRejectSpike
   - Trigger: Prometheus — `sum(rate(http_server_requests_seconds_count{service="pos-mcp-server",
     uri=~"/v1/(mcp|nlt)/.*", status="403"}[10m])) /
     clamp_min(sum(rate(http_server_requests_seconds_count{service="pos-mcp-server",
     uri=~"/v1/(mcp|nlt)/.*"}[10m])), 0.0001) > 0.1` for 10 minutes. Supplement with Loki:
     `quantile_over_time(0.05, TELEMETRY | unwrap actor_permissionCodeCount [10m]) == 0`, which
     catches a broken gateway bitset decode before it surfaces as a 403.
     `tools.rejectedPermissionCount` cannot back this alert — the factory hardcodes it to 0, and
     `PermissionAwareMetadataFilter` logs its drop count only at DEBUG (deployed baseline is INFO per
     ADR-0046).
   - Severity: P1 (a permission-resolution outage looks like mass user error)
   - Runbook: pos-mcp-server/docs/runbooks/authz-outage.md

10. Name: NltiRagRecallDegraded
    - Trigger: **Today** — Loki, the DOMAIN prompt-layer share as the grounding proxy. Fire when the
      share falls below 0.5 with at least 100 requests in the window:

      ```logql
      sum(count_over_time({...} | json prompt_layers="rag.promptLayers"
            | prompt_layers =~ `"DOMAIN"` [1h]))
        / clamp_min(sum(count_over_time(TELEMETRY [1h])), 1)
      ```

      Plus any static-corpus preload failure,
      `{job="docker", service="pos-mcp-server", level="WARN"} |= "Preload failed for document_id="`.
      **Once retrieval scores are emitted** — Loki:
      `quantile_over_time(0.5, {...} | json top_score="rag.retrieved[0].score" | unwrap top_score [1h])
       < 0.45` (the `MCP_RAG_MIN_SCORE` floor). The telemetry form cannot fire yet: the factory always
      passes `null` for `Rag.retrieved`.
    - Severity: P2
    - Runbook: pos-mcp-server/docs/runbooks/planning-failure.md

11. Name: NltiWriteConfirmationFailures
    - Trigger: Prometheus — non-2xx outcomes on the write-gate endpoints:
      `sum by (status) (rate(http_server_requests_seconds_count{service="pos-mcp-server",
      uri=~"/v1/nlt/requests/\\{requestId\\}/(confirm|cancel)", status=~"4..|5.."}[15m])) > 0`.
      Treat separately: any `5xx` (`WRITE_PLAN_EXECUTION_FAILED` — a downstream write may be
      half-applied) is P1; a sustained `409`/`410` rate above 20 % of confirms
      (`WRITE_PLAN_CONFLICT` / `WRITE_PLAN_STALE` / `WRITE_PLAN_EXPIRED`) is P2 and points at a TTL or
      stale-source-version problem. Complement with Loki
      `sum(count_over_time(TELEMETRY | write_isWrite = "true" [15m]))` versus
      `sum(count_over_time({job="docker", service="pos-mcp-server"} |= "NLTI write plan created" [15m]))`
      — write intents detected but no plan created is its own defect.
      **Outcome-based form (preferred, live since #1397)** — Prometheus:
      `sum by (outcome) (rate(nlt_write_plan_confirmation_count_total{service="pos-mcp-server"}[15m]))`.
      `NltiWritePlanService` increments this counter, tagged with the same five outcomes it writes to
      the audit ledger (`confirmed`/`cancelled`/`expired`/`stale-data`/`superseded`), so the alert can
      finally name the failure mode instead of inferring it from an HTTP code: a rising
      `outcome="stale-data"` or `outcome="expired"` share is the TTL / stale-source signal directly.
      Keep the HTTP form alongside it — a confirm that passes the gate and then fails downstream is
      `outcome="confirmed"` with a `5xx`, so only the status query sees it. The same outcomes ride the
      telemetry stream as `write.confirmationOutcome` for the Gate 7 panel; the counter is what alert
      rules use, since Prometheus cannot query Loki.
    - Severity: P1 for 5xx, P2 for the conflict/expiry ratio
    - Runbook: pos-mcp-server/docs/runbooks/confirmation-gate-mismatch.md

## Implementation Notes

- Rules 1–4 and 9, 11 are Prometheus-evaluable; 5–8, 10 are Loki (LogQL) rules. The Loki half is
  materialized (#1424): `observability/loki-config.yml` configures the ruler, and the rule file
  `observability/loki/rules/nlti-alerts.yml` (compose-mounted at `/loki/rules/fake`) carries rules
  2, 5–8, 10 and the Loki supplements of 9 and 11. That file is the deployed form; this document
  stays the source of truth for intent and thresholds — change both together. Verify with
  `curl loki:3100/loki/api/v1/rules` (loaded rule files) and `curl loki:3100/prometheus/api/v1/rules`
  (evaluation state). No Alertmanager is deployed and Grafana's embedded Alertmanager only accepts
  Grafana-managed alerts, so ruler alert state is dashboard/UI-only (Grafana → Alerting → Alert
  rules, via the Loki datasource) until an Alertmanager is added and `ruler.alertmanager_url` set.
- Every Loki rule filters `service="pos-mcp-server"`, the label Promtail derives from the Docker
  Compose service name; it is identical on the local stack and on alpha.
- Volume guards matter: the NLTI traffic floor on alpha is low enough that ratio alerts will flap
  without a minimum-request-count condition. Every ratio rule above states its guard.
- Rules 7, 9, 10 and 11 name both the signal available today and the telemetry field that will
  replace it. When the corresponding emitter gap closes (see the gap table in
  `docs/dashboards/nlti-overview.md`), switch the rule and delete the substitute.
- Connect each alert to the on-call rotation and notification channels.
