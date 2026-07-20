# Brownfield Assessment

Generated: 2026-07-17
Scope: All 28 deployable `pos-*` services (Maven multi-module reactor, `durion-positivity-backend`)
Source state: `api-only-no-spans` (uniform across all 28 services, per [docs/sre-todo.md](../sre-todo.md) and [docs/generated/audit/current/context.json](../generated/audit/current/context.json))

> Detection was performed by the SRE Planner and is treated as canonical input here.
> This assessment does not re-run detection tooling — it inventories and classifies
> what the Planner found, and adds file/line-level detail for the one non-trivial
> manual instrumentation site (`pos-mcp-server`).

## Recommendation: Preserve and extend (all 28 services)

Every service has the Grafana OpenTelemetry Java Agent v2.9.0 attached via `-javaagent`,
zero manual SDK initialization, and (with one exception) zero manual spans or
`app.operation.*` attributes. There is nothing non-conformant to replace and nothing to
migrate off of — the correct path for all 28 services is to **keep the agent exactly as
configured** and layer OTel **API-only** business spans on top of it (Phase 3a), per
[.sre/docs/standards/auto-instrumentation-api-pattern.md](../../.sre/docs/standards/auto-instrumentation-api-pattern.md).

`pos-mcp-server` is the one service with existing manual OTel API usage. That usage is
not a business span (see Span Inventory) and needs targeted attribute-naming fixes, not
a rewrite. It does not change the overall "preserve and extend" recommendation.

## SDK Setup Assessment (uniform across all 28 services)

- **Provider:** none — no `OpenTelemetrySdk.builder()`, `SdkTracerProvider`, or
  `BatchSpanProcessor` found anywhere in service source. Confirmed no double-init risk.
- **Exporter:** OTLP HTTP/protobuf, agent-managed — `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318`,
  `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`, set as Dockerfile `ENV`/`ARG` (e.g.
  [pos-order/Dockerfile](../../pos-order/Dockerfile#L13-L15), [pos-api-gateway/Dockerfile](../../pos-api-gateway/Dockerfile#L13-L15) — identical pattern confirmed by direct read).
  Note: `sre.config.yaml` declares `http://localhost:4318` as the "local" destination — this is a
  Planner-flagged blocker (endpoint discrepancy), not an instrumentation defect; no action needed
  from this assessment.
- **Propagators:** agent default (W3C Trace Context + W3C Baggage) — not overridden in code or env
  vars in any service reviewed.
- **Sampler:** agent default — no `OTEL_TRACES_SAMPLER*` env vars set in any Dockerfile reviewed;
  effectively `parentbased_always_on`. Not a defect for the current phase, but worth a follow-up
  question for the Observability Engineer (Phase 2) once traffic volume/cost is considered.
- **Resource config:** env-vars, entirely agent-managed. One minor redundancy: every Dockerfile
  sets both `OTEL_RESOURCE_ATTRIBUTES="service.name=DURION-POSITIVITY-BACKEND,...` **and**
  `OTEL_SERVICE_NAME=<pos-service-name>` (e.g. [pos-order/Dockerfile](../../pos-order/Dockerfile#L11-L12)).
  Per OTel spec, `OTEL_SERVICE_NAME` takes precedence, so the effective `service.name` is correct
  (e.g. `pos-order`), but the redundant/contradictory value in `OTEL_RESOURCE_ATTRIBUTES` is
  confusing and should be removed to avoid future misconfiguration. Classified **Fixable**, not
  **Must-fix** (no functional impact today).
- **Auto-agent:** present in all 28 services — Grafana OpenTelemetry Java Agent v2.9.0,
  downloaded in-image and attached via `-javaagent:/opt/grafana-opentelemetry-java.jar` at
  `ENTRYPOINT` (identical pattern in every `pos-*/Dockerfile`).
- **Verdict: Conformant.** No SDK init exists to conflict with the agent; no action required
  before Phase 3a other than the two `Fixable` cleanup notes above.

## Per-Service Assessment

All 28 services share identical SDK Setup Assessment above. Table below records
per-service instrumentation state and the specific finding, if any, beyond the uniform pattern.

| # | Service | State | Manual spans/attrs found | Recommendation |
|---|---------|-------|---------------------------|-----------------|
| 1 | pos-accounting | api-only-no-spans | none | Preserve and extend |
| 2 | pos-api-gateway | api-only-no-spans | none | Preserve and extend |
| 3 | pos-bulk-loader | api-only-no-spans | none | Preserve and extend |
| 4 | pos-catalog | api-only-no-spans | none | Preserve and extend |
| 5 | pos-customer | api-only-no-spans | none | Preserve and extend |
| 6 | pos-documents | api-only-no-spans | none | Preserve and extend |
| 7 | pos-event-receiver | api-only-no-spans | none | Preserve and extend |
| 8 | pos-events | api-only-no-spans | none | Preserve and extend |
| 9 | pos-image | api-only-no-spans | none | Preserve and extend |
| 10 | pos-inquiry | api-only-no-spans | none | Preserve and extend |
| 11 | pos-inventory | api-only-no-spans | none | Preserve and extend |
| 12 | pos-invoice | api-only-no-spans | none | Preserve and extend |
| 13 | pos-location | api-only-no-spans | none | Preserve and extend |
| 14 | **pos-mcp-server** | api-only-no-spans | **yes — attribute enrichment only, see Span Inventory** | Preserve and extend; fix attribute naming (see Must-fix) |
| 15 | pos-order | api-only-no-spans | none | Preserve and extend |
| 16 | pos-people | api-only-no-spans | none | Preserve and extend |
| 17 | pos-people-contact | api-only-no-spans | none | Preserve and extend |
| 18 | pos-price | api-only-no-spans | none | Preserve and extend |
| 19 | pos-security-service | api-only-no-spans | none | Preserve and extend |
| 20 | pos-service-discovery | api-only-no-spans | none | Preserve and extend |
| 21 | pos-shop-manager | api-only-no-spans | none | Preserve and extend |
| 22 | pos-tax | api-only-no-spans | none | Preserve and extend |
| 23 | pos-vehicle-fitment | api-only-no-spans | none | Preserve and extend |
| 24 | pos-vehicle-inventory | api-only-no-spans | none | Preserve and extend |
| 25 | pos-vehicle-reference-carapi | api-only-no-spans | none | Preserve and extend |
| 26 | pos-vehicle-reference-nhtsa | api-only-no-spans | none | Preserve and extend |
| 27 | pos-warranty | api-only-no-spans | none | Preserve and extend |
| 28 | pos-workorder | api-only-no-spans | none | Preserve and extend |

> **Data-quality note (not an instrumentation defect):** [docs/generated/audit/current/context.json](../generated/audit/current/context.json)'s
> `detected_services` array lists 27 entries and omits `pos-order`, even though
> `docs/sre-todo.md` cites `pos-order/Dockerfile` as the canonical evidence example and the repo
> contains `pos-order/Dockerfile` with the identical agent pattern (confirmed by direct read,
> same `-javaagent`/env-var structure as `pos-api-gateway` and all other services). `pos-order`
> is included as row 15 above to reconcile the "28 services" count stated in `sre-todo.md`. Flag
> this gap to the SRE Planner so `context.json` is regenerated with the complete list.

## Span Inventory

| Span name | Location | Kind | Attributes | Classification | Notes |
|-----------|----------|------|------------|-----------------|-------|
| *(none — 27 services)* | all services except pos-mcp-server | n/a | n/a | n/a | No manual spans exist. All spans currently observed are auto-instrumentation agent spans (HTTP server/client, JDBC, Kafka producer/consumer) — out of scope for this assessment; Phase 3a will add the first business spans. |
| *(no dedicated business span — attribute enrichment on auto-span)* | [NltiRequestServiceImpl.java:96](../../pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/NltiRequestServiceImpl.java#L96), attributes set at [L101-L102](../../pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/NltiRequestServiceImpl.java#L101-L102) and [L124-L125](../../pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/NltiRequestServiceImpl.java#L124-L125) | SERVER (inherited — `Span.current()` returns the agent-created HTTP server span for the inbound NLTI request; no `tracer.spanBuilder(...)` call exists) | `nlt.correlationId`, `nlt.userId`, `nlt.requestId`, `nlt.sessionId` (keys defined in [NltiSpanAttributes.java](../../pos-mcp-server/src/main/java/com/positivity/mcp/internal/observability/NltiSpanAttributes.java)) | **Fixable** | Not a business span — `submit()` never creates a child span, so there is no `app.operation.*`-bearing span for the "submit NLTI request" operation at all. Attribute naming also deviates from the taxonomy (see Must-fix below: `nlt.userId` should be `enduser.id`; `nlt.sessionId` collides with the taxonomy's explicitly forbidden `session.id` pattern). Recommend: (1) keep the correlation-id enrichment on the agent span (harmless, useful), (2) add a proper child span (e.g. `Submit Nlti Request`) with `app.operation.name`/`app.operation.outcome` in Phase 3a once `operations.yaml` catalogs this operation, (3) rename attributes per Must-fix. |

## Metric Inventory

| Metric name | Type | Labels | Classification | Notes |
|-------------|------|--------|-----------------|-------|
| `nlt.request.count` | Micrometer counter | none | Fixable | Emitted via `MeterRegistry` (Micrometer), not the OTel Metrics API — this is a separate, valid signal path (Micrometer → agent's Micrometer bridge / Prometheus scrape), not a double-init concern. Naming doesn't yet follow an `app.operation.*`-aligned convention; align once `operations.yaml` exists (Phase 4), not blocking. |
| `nlt.error.count` | Micrometer counter | none | Fixable | Same as above. No `outcome`/`failure_reason` label — cannot compute a per-reason error-budget breakdown yet. |
| `nlt.request.latency_ms` | Micrometer timer | none | Fixable | Same as above; candidate SLI latency source for `pos-mcp-server` Tier 1 ops once catalogued. |
| `nlt.rate_limit.session_counter.evictions` / `nlt.rate_limit.session_counter.invalidations` | Micrometer counter | none | Conformant | Internal cache-health metrics (Caffeine cache eviction/invalidation counts), low cardinality, no PII/ID labels. No change needed. |

## Operations Coverage

`docs/domain/operations.yaml` does not exist yet (Phase 1 has not run). Coverage/gap analysis
against it is not yet possible. Candidate operations discovered during this assessment for the
Repo Domain Analyst to catalogue in Phase 1:

| Candidate operation | Evidence | Notes |
|----------------------|----------|-------|
| Submit Nlti Request | [NltiRequestServiceImpl.submit()](../../pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/NltiRequestServiceImpl.java#L88-L131) | Only operation in the repo with any existing manual telemetry hook; natural first Tier-1 candidate for `pos-mcp-server` once `operations.yaml` exists. |

No other candidate business operations were surfaced by this assessment — the other 27 services
have zero manual instrumentation to derive operation candidates from; Phase 1 domain analysis
(source/OpenAPI-driven) is the source of truth for their operation catalogs.

## Must-fix Items (immediate attention)

1. **`nlt.sessionId` attribute conflicts with the attribute taxonomy's forbidden pattern.**
   [attribute-taxonomy.md](../../.sre/docs/standards/attribute-taxonomy.md) explicitly forbids
   `session.id`-style attributes ("High cardinality + session tracking risk") and directs:
   "Session correlation: use trace context propagation (`traceparent`) instead of custom session
   IDs." `NltiSpanAttributes.NLT_SESSION_ID` ("`nlt.sessionId`", set at
   [NltiRequestServiceImpl.java:125](../../pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/NltiRequestServiceImpl.java#L125))
   is functionally the same pattern under a custom namespace. This must be resolved before Phase
   3a extends this service's instrumentation further — either (a) drop the attribute and rely on
   trace-context propagation for session correlation, or (b) file a taxonomy PR to add a governed,
   bounded `app.*` (or `enduser.*`) session attribute with an explicit cardinality justification,
   per the taxonomy's "Adding New Attributes" process. Do not carry this pattern into any new
   service's instrumentation in Phase 3a until decided.
2. **`nlt.userId` should be `enduser.id`, not a custom key.** Taxonomy explicitly forbids `user.id`
   ("Non-standard - use `enduser.id`"). `NLT_USER_ID` ("`nlt.userId`", set at
   [NltiRequestServiceImpl.java:102](../../pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/NltiRequestServiceImpl.java#L102))
   carries the authenticated username and should be renamed to the governed `enduser.id` attribute
   (span/log attribute only, per taxonomy — never a metric label). Lower severity than item 1 (no
   ban on the underlying data, just the key name), but grouped as must-fix since it is the same
   root cause (non-standard `nlt.*` namespace for user/session identity) and should be fixed in the
   same pass.

No `double-init` conditions exist in any service (confirmed by Planner detection and this
assessment — no `TracerProvider`/`SdkTracerProvider`/`BatchSpanProcessor` construction found
anywhere in source). No credential leakage, raw SQL, or unbounded query-string attributes were
found in the one manual instrumentation site reviewed.

## Migration Steps (ordered)

1. **`pos-mcp-server`** — rename `NltiSpanAttributes.NLT_SESSION_ID` usage: remove the `nlt.sessionId`
   span attribute at [NltiRequestServiceImpl.java:125](../../pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/NltiRequestServiceImpl.java#L125)
   (and the corresponding key definition/test in
   [NltiSpanAttributes.java](../../pos-mcp-server/src/main/java/com/positivity/mcp/internal/observability/NltiSpanAttributes.java) /
   [NltiTracingTest.java](../../pos-mcp-server/src/test/java/com/positivity/mcp/internal/observability/NltiTracingTest.java#L47-L49)),
   or replace it with a taxonomy-approved attribute once the SRE team decides between dropping it
   or petitioning the taxonomy (Must-fix item 1). Do this before or during Phase 3a for this
   service.
2. **`pos-mcp-server`** — rename `NltiSpanAttributes.NLT_USER_ID` ("`nlt.userId`") to
   `enduser.id` at [NltiRequestServiceImpl.java:99-102](../../pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/NltiRequestServiceImpl.java#L99-L102)
   and update the corresponding test assertion in
   [NltiTracingTest.java:41-43](../../pos-mcp-server/src/test/java/com/positivity/mcp/internal/observability/NltiTracingTest.java#L41-L43).
   `nlt.correlationId` and `nlt.requestId` may remain as-is (span-attribute-only IDs are an
   accepted pattern per taxonomy's `messaging.message.id` precedent) unless the Observability
   Engineer (Phase 2) decides to standardize them under a documented `app.*` namespace.
3. **`pos-mcp-server`** — once `operations.yaml` catalogues "Submit Nlti Request" (Phase 1), add a
   proper child span (`tracer.spanBuilder("Submit Nlti Request")`) wrapping the body of
   `submit()` with `app.operation.name`/`app.operation.outcome`/`app.operation.failure_reason`,
   instead of only enriching the inherited HTTP server span. Keep the existing correlation-id
   enrichment on the parent span; do not remove it.
4. **All 28 services** — Phase 3a proceeds directly to adding OTel-API-only business spans per
   `operations.yaml` (once produced in Phase 1). No preparatory cleanup is required for the other
   27 services — there is nothing to migrate or remove.
5. **Optional cleanup (non-blocking):** remove the redundant/misleading
   `service.name=DURION-POSITIVITY-BACKEND` value from every `OTEL_RESOURCE_ATTRIBUTES` env var
   (e.g. [pos-order/Dockerfile:11](../../pos-order/Dockerfile#L11)), since `OTEL_SERVICE_NAME`
   already sets the correct, per-service value and takes precedence. Suggest raising as a
   follow-up ticket rather than blocking Phase 3a.
6. **Data-quality follow-up (non-blocking):** ask the SRE Planner to regenerate
   `docs/generated/audit/current/context.json` so its `detected_services` array includes
   `pos-order` (currently omitted — see note under Per-Service Assessment).

---

# Frontend (durion-positivity-frontend)

Generated: 2026-07-17
Scope: `durion-positivity-frontend` (Angular 21, standalone components, Angular Signals, SSR via
`@angular/ssr` + Express) — sibling repo at
`/Users/matthewlewis/Downloads/durion-positivity-frontend-master`, added to SRE workflow scope
after the original Phase 0b run covered only the 28 `pos-*` backend services above.
Source state: `greenfield` (no Planner detection entry exists yet for this repo in
`docs/sre-todo.md` at the time of this assessment; classification below is derived directly from
source inspection per this agent's standard process for repos without prior Planner detection).

> This section was added without modifying any content above. It follows the same
> structure/classification scheme used for the backend assessment.

## Recommendation: Greenfield — instrument directly with OTel Web SDK / Faro, no migration needed

No tracing, RUM, APM, or telemetry SDK of any kind — OTel or otherwise — is present anywhere in
this repo, browser-side or server-side (SSR). There is nothing to preserve, patch, or remove.
Phase 3 instrumentation agents can install and configure `@opentelemetry/*` (or Grafana Faro Web
SDK) from a clean slate.

## SDK Setup Assessment

- **Provider:** none. No `@opentelemetry/sdk-trace-web`, `WebTracerProvider`, `NodeTracerProvider`,
  or any `TracerProvider`/`MeterProvider` construction found in `src/` or `package.json`.
- **Exporter:** none configured.
- **Propagators:** none configured — no W3C Trace Context / Baggage propagator setup found; the
  `auth.interceptor.ts` ([auth.interceptor.ts](../../../durion-positivity-frontend-master/src/app/core/interceptors/auth.interceptor.ts))
  only attaches `Authorization: Bearer <token>`, no `traceparent`/`tracestate` header injection.
- **Sampler:** n/a (no SDK present).
- **Resource config:** n/a.
- **Auto-agent / auto-instrumentation:** absent on both sides:
  - Browser: no `@opentelemetry/auto-instrumentations-web`, no Grafana Faro (`@grafana/faro-web-sdk`),
    no Sentry/Datadog RUM/LogRocket — confirmed absent from `dependencies`/`devDependencies` in
    [package.json](../../../durion-positivity-frontend-master/package.json).
  - Server (SSR, `src/server.ts`): no `-r @opentelemetry/auto-instrumentations-node/register` or
    equivalent preload, no `NODE_OPTIONS` instrumentation flag, no request-logging/tracing
    middleware (no morgan/pino-http/otel middleware) in
    [server.ts](../../../durion-positivity-frontend-master/src/server.ts); confirmed no OTel env
    vars or instrumentation flags in the runtime `CMD`/`ENV` of
    [Dockerfile](../../../durion-positivity-frontend-master/Dockerfile).
- **Verdict: Greenfield.** No SDK, no partial init, nothing to conflict with a future install —
  zero risk of double-init.

## Existing Signal Inventory (non-OTel, informational only)

No spans, metrics, or structured/correlated logs exist. The items below are plain
`console.*` diagnostics with no trace/span correlation and no backend forwarding — they are
**not** an instrumentation signal in the OTel sense and are not migration candidates. Listed for
completeness only.

| Location | Kind | Classification | Notes |
|----------|------|-----------------|-------|
| [auth.service.ts:75](../../../durion-positivity-frontend-master/src/app/core/services/auth.service.ts#L75) | `console.warn` | Irrelevant | Dev-only warning when `mockAuth` is enabled; local diagnostic, not a log signal. |
| [create-commercial-account.component.ts:140](../../../durion-positivity-frontend-master/src/app/features/crm/pages/create-commercial-account/create-commercial-account.component.ts#L140) | `console.error` | Irrelevant | Clipboard-write failure; UI-local, no correlation ID. |
| [location-inventory-overview-page.component.ts:465,595,600](../../../durion-positivity-frontend-master/src/app/features/inventory/pages/by-location/location-overview/location-inventory-overview-page.component.ts#L465) | `console.warn`/`console.error` | Irrelevant | Rollup validation/load errors; component-local, no trace_id/span_id. |
| [site-inventory-tree-page.component.ts:259](../../../durion-positivity-frontend-master/src/app/features/inventory/pages/by-location/site-tree/site-inventory-tree-page.component.ts#L259) | `console.error` | Irrelevant | Same pattern as above. |
| [chat-send.service.ts:116,126](../../../durion-positivity-frontend-master/src/app/features/shell/services/chat-send.service.ts#L116) | `console.error` | Irrelevant | Chat backend request failure; no correlation ID or backend log shipping. |
| [main.ts](../../../durion-positivity-frontend-master/src/main.ts) | `bootstrapApplication(...).catch(console.error)` | Irrelevant | Bootstrap failure fallback; standard Angular pattern, no telemetry. |
| `provideBrowserGlobalErrorListeners()` in [app.config.ts:66](../../../durion-positivity-frontend-master/src/app/app.config.ts#L66) | Angular built-in global error/unhandled-rejection listener | Non-conformant (as a telemetry source) | Routes uncaught errors to Angular's default `ErrorHandler`, which only logs to console — no custom `ErrorHandler` override, no forwarding to any backend or RUM collector. Good integration point for a future OTel/Faro error exporter, not an existing signal to migrate. |
| `withNavigationErrorHandler(...)` in [app.config.ts:70-75](../../../durion-positivity-frontend-master/src/app/app.config.ts#L70-L75) | Angular Router chunk-load recovery ([chunk-error-recovery.ts](../../../durion-positivity-frontend-master/src/app/core/router/chunk-error-recovery.ts)) | Irrelevant | UX resilience feature (reloads stale tabs after a deploy), not an observability signal. |

## Metric Inventory

None found. No counters, histograms, or gauges of any kind (Web Vitals, custom business metrics,
or otherwise) exist in the repo.

## Operations Coverage

`docs/domain/operations.yaml` (backend) does not currently model any frontend-originated
operations, and no frontend-side operations catalog exists yet. Coverage/gap analysis is not
applicable until a frontend domain analysis pass runs. No orphan spans exist to reconcile (there
are no spans at all).

## Must-fix Items (immediate attention)

None. No double-init risk, no PII-bearing telemetry attributes, no wrong propagators — because no
instrumentation exists at all.

## Migration Steps (ordered)

None required — this is a clean install, not a migration. Recommended next steps for the
instrumentation phase (not part of this assessment):

1. Choose a browser RUM approach (`@opentelemetry/sdk-trace-web` + auto-instrumentations-web, or
   Grafana Faro Web SDK per this repo's Grafana-centric backend stack) and initialize it once,
   early in [main.ts](../../../durion-positivity-frontend-master/src/main.ts) or as an
   `ApplicationConfig` provider in [app.config.ts](../../../durion-positivity-frontend-master/src/app/app.config.ts) —
   there is no existing init to conflict with.
2. Instrument the SSR Express server in [server.ts](../../../durion-positivity-frontend-master/src/server.ts)
   with `@opentelemetry/sdk-node` + `@opentelemetry/auto-instrumentations-node` (HTTP server spans
   for SSR renders and the `/api`, `/mcp-server` proxy routes).
3. Propagate W3C trace context from the browser through `auth.interceptor.ts` (or a dedicated
   tracing interceptor) so frontend spans link to the backend spans already emitted by the Grafana
   OTel Java Agent on the `pos-*` services above.
4. Replace the ad hoc `console.error`/`console.warn` calls listed in Existing Signal Inventory
   with structured, correlated logging (or leave them as local dev diagnostics and rely on spans/
   error events for production observability) — a judgment call for the Observability Engineer,
   not a blocking item.
5. Register a custom Angular `ErrorHandler` (or hook into `provideBrowserGlobalErrorListeners()`'s
   underlying error stream) to forward uncaught errors as OTel error span events / Faro exceptions
   once the SDK from step 1 is in place.
