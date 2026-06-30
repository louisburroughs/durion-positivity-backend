# Gate 7 — Admin Tooling, Dashboards, Tuning Controls (design)

> **Status:** DESIGN. Makes the system operable, auditable, and tunable without uncontrolled drift.
> Verification is live (admin flows, dashboards, shadow→live tuning) — runbook §B.10. Final gate.

## Grounded building blocks
- `NltiObservabilityMetricsConfig` — Micrometer `Timer`s (`nlt.request/planning/execution.latency`,
  `mcp.tool.execution.latency`, `mcp.model.latency`). LatencyUtils dep fixed (boot works).
- Prometheus exposure: `management.endpoints.web.exposure.include = health,info,metrics,prometheus`.
- `nlti.request.telemetry` v1 (Gate 0) — structured per-request event (routing, tier, model,
  tools selected/rejected, RAG docs, prompt layers, write outcome, latency).
- `ToolPriorityTuningService` — `@ConditionalOnProperty(mcp.tuning.enabled, havingValue=true)`;
  **disabled by default since Gate 0**. Daily cron recomputes `mcp_tool.priority`.
- `mcp_tool_permission` — populated only by Flyway V18 today (#785: no runtime curation).
- `AuditLedgerService`; existing `docs/dashboards/nlti-overview.md`, `docs/alerts/nlti-alerts.md`,
  `docs/runbooks/*`.

## G7.1 — Admin endpoints for `mcp_tool_permission` (#785)
RBAC-guarded CRUD so authorized admins curate tool↔permission mappings at runtime instead of
shipping a migration.
- Endpoints (e.g. `/v1/mcp/admin/tool-permissions`): list / add / remove a `(tool, permission_code)`.
- New permission codes `mcp:tool_permission:view` / `mcp:tool_permission:manage` in
  `permissions.yaml`. **Catalog gotcha:** also register them in the perm-bits catalog
  (`generate-permissions.py --sync`) or the grant is silently dropped → 403; a `CATALOG_VERSION` bump
  is a fleet redeploy. `@PreAuthorize` on the endpoints.
- **Audited:** every change emits an `AuditLedgerService` record (who / what / old→new / when).
- **TTL/cache-safe:** a mapping change takes effect after the agent-cache TTL
  (`mcp.agent.cache-ttl-minutes`) or an explicit cache invalidation — never mid-request inconsistency.

## G7.2 — Dashboards over telemetry
Drive dashboards from **structured signals**, not ad-hoc log scraping. Two sources:
- Micrometer meters (Prometheus): the existing latency timers + **new counters/gauges** emitted
  alongside `nlti.request.telemetry`: `mcp.router.tier` (tag: tier), `mcp.tools.permission_rejected`,
  `mcp.model.fallback` (tag: used), `mcp.write.outcome` (tag: confirmed|cancelled|expired|failed),
  `mcp.prompt.layer` (tag: layer), `mcp.rag.recall` (gauge from harness runs).
- Dashboard panels (extend `docs/dashboards/nlti-overview.md`): routing decisions, model-tier usage,
  fallback usage, tool-selection quality (hit@5/MRR from harness), permission rejects, RAG recall,
  prompt-layer usage, write confirmations / cancellations / expirations / failures, latency p50/p95.

## G7.3 — Alerts (`docs/alerts/nlti-alerts.md`, Prometheus rules)
- spike in failed tool calls · spike in permission rejects · fallback overuse · write-failure rate ·
  confirmation-mismatch attempt (Gate 6 stale/expired re-parse guard) · retrieval regression
  (recall@k below threshold) · latency SLO breach (p95).

## G7.4 — Adaptive tuning controls (off → shadow → controlled-live)
Replace the boolean `mcp.tuning.enabled` with `mcp.tuning.mode = off | shadow | live` (keep the env
override). Current default stays **off** (Gate 0).
- **shadow:** the cron computes tuned priorities and writes them to a shadow column / log
  (`mcp_tool.shadow_priority` or a `mcp_tool_tuning_run` table) and compares against live — **does not
  mutate `mcp_tool.priority`**.
- **live:** promotion of shadow → live requires the #783 eval harness to show improvement (or an
  approved neutral result); a failing eval blocks promotion. Manual approval gate.
- Telemetry/dashboard shows shadow-vs-live deltas so promotion is an informed decision.

## Drift guards (Gate 7 locks)
- Admin endpoints never bypass audit; always permission-gated.
- Permission edits are TTL/cache-safe.
- Adaptive tuning cannot silently mutate priorities (shadow first; live gated by evals).
- Dashboards consume structured telemetry/meters, not ad-hoc logs.
- Runbooks present and current.

## Verification (live — runbook §B.10)
- Unauthorized user cannot reach admin endpoints (403).
- An admin mapping change takes effect after cache invalidation / TTL.
- Audit log records who changed what and when.
- Shadow tuning does not mutate live priority; live tuning cannot promote if eval thresholds fail.
- Dashboard numbers reconcile with `nlti.request.telemetry` events.

## Implementation order
G7.2 meters (emit alongside telemetry) → G7.1 admin endpoints (+ perms + catalog sync + audit +
cache-safe) → G7.3 alert rules → G7.4 tuning mode (shadow store + promotion gate) → dashboards/runbook
updates → live §B.10. Promote tuning to live only after Gates 0–6 are signed and the harness baseline
is trustworthy.
