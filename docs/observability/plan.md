# Observability Plan: durion-positivity-backend (+ durion-positivity-frontend)

Generated: 2026-07-17 (Phase 2 - Observability Engineer)
Mode: consumed (`framework.path: .sre`, `outputs.root: .`)
Inputs used: [docs/sre-todo.md](../sre-todo.md), [docs/domain/domain-map.md](../domain/domain-map.md),
[docs/domain/operations.yaml](../domain/operations.yaml), [sre.config.yaml](../../sre.config.yaml),
[docs/domain/brownfield-assessment.md](../domain/brownfield-assessment.md),
`.sre/packs/recipes/spans/`, `.sre/packs/recipes/slis/`, `.sre/docs/standards/attribute-taxonomy.md`.

This plan covers **one coherent system**: 24 backend `pos-*` bounded contexts plus bounded context
#25 (Frontend Application Shell / `durion-positivity-frontend`, Angular 21). Both surfaces share the
same `operations.yaml` catalog, the same attribute taxonomy, and the same trace.

## 0. State this plan builds on (do not re-detect, do not re-init)

| Surface | State | What this means for instrumentation |
|---|---|---|
| Backend (28 `pos-*` services) | `api-only-no-spans` | Grafana OTel Java Agent v2.9.0 already attached via `-javaagent`. It auto-creates HTTP server spans (`http-handler`), HTTP client spans (`outbound-http`), DB client spans (`db-call`), and Kafka producer/consumer spans. **No SDK re-init. No new auto-instrumentation config.** Manual work is limited to adding `internal`-recipe business spans via the OTel API (`Tracer.spanBuilder(...)`) as children of the spans the agent already creates. |
| Frontend (Angular 21) | `greenfield` | No existing telemetry. Direct instrumentation with the Grafana Faro Web SDK — set up via the vendored `faro-web-setup` skill, which wraps the OTel Web SDK — is proposed from scratch — nothing to preserve or migrate. |
| GenAI (`pos-mcp-server` only, LangChain4j + Ollama) | `none` (no `gen_ai.*` yet) | Scoped lightly here per Phase 2g/3g division of labor — see [§9](#9-genai-observability). |

## 1. Tier classification (confirmed from operations.yaml)

Tiers in `operations.yaml` match the domain map's criticality classification; no adjustments needed.

| Tier | Definition | Backend bounded contexts | Frontend footprint |
|---|---|---|---|
| **Tier 1** | Revenue/transaction-critical, full traces + metrics + logs | order-management, pricing, catalog (partial), inventory, accounting (partial), customer-crm (partial), invoicing, work-order-execution, warranty (partial), tax, security-identity, gateway | No dedicated frontend Tier‑1 operation exists in `operations.yaml` — Tier 1 *coverage* is delivered by RUM spans on frontend routes that call Tier‑1 backend operations (order cart, price override, invoice finalize/pay, estimate approval; see [§5.3](#53-frontend-tier1-coverage-by-correlation)) |
| **Tier 2** | Supporting operations, traces + key metrics | shop-scheduling, warranty (partial), vehicle-inventory, vehicle-fitment (partial), workforce, people-identity, location, event-backbone, genai-assistant (`submit-nlti-request`, `stream-mcp-chat`) | `view-operations-dashboard`, `view-customer-snapshot`, `view-work-in-progress-board`, `view-dispatch-board`, `view-security-audit-log`, `view-inventory-by-location` |
| **Tier 3** | Background/basic metrics + error logs | vehicle-reference-carapi/nhtsa, documents, image, bulk-loader (partial), genai-assistant (`ingest-mcp-document`) | `complete-bulk-import-job`, `view-timekeeping-discrepancy-report` |

## 2. Recipe inventory and coverage

Checked `.sre/packs/recipes/spans/` and `.sre/packs/recipes/slis/` before proposing any shape, per
agent instructions.

| Recipe | Category | Used by |
|---|---|---|
| `http-handler` | span | 49 of 50 Tier‑1 backend operations (every synchronous REST entrypoint) — this is the **auto-instrumented** server span, not the manual one (see [§3](#3-signal-strategy-per-tier)) |
| `queue-consumer` | span | `reconcile-invoice-event` (Tier 1, accounting) and `reconcile-workorder-manifest` (Tier 2, customer-crm) — Kafka domain-event consumers, auto-instrumented by the agent's Kafka instrumentation |
| `internal` | span | **The manual business span for every operation.** Pattern `{Verb} {BusinessObject}` matches every `business_span` value already recorded in `operations.yaml` (e.g. `Create Sales Order`, `Post Journal Entry`) |
| `outbound-http` | span | Auto-instrumented by the agent for every `downstream[].service` REST call recorded in `operations.yaml` (e.g. `pos-order` → `pos-price`) — no manual work required, validate only |
| `db-call` | span | Auto-instrumented by the agent for JPA/Hibernate repository calls — no manual work required, validate only |
| `ai-invoke-agent`, `ai-llm-call`, `ai-tool-execution`, `ai-rag-retrieval`, `ai-agent-step` | span | `pos-mcp-server` GenAI operations — scoped for Phase 3g, acknowledged in [§9](#9-genai-observability) |
| `success-rate` | SLI | Availability SLI for all 50 Tier‑1 backend operations + all 8 frontend operations |
| `p95-latency` | SLI | Latency SLI for every operation with a `threshold_ms` (all Tier 1/2 backend ops; 6 of 8 frontend ops) |
| `freshness` | SLI | Not currently used — no batch/async-freshness operation is cataloged; available for future use (e.g. bulk-import job completion lag) |
| `ai-token-throughput`, `ai-ttft`, `ai-quality-score` | SLI | GenAI-specific — deferred to Phase 4/2g for `pos-mcp-server` |

**No new span or SLI recipe is required for the backend or the frontend.** Every backend operation
resolves cleanly to `http-handler` + `internal` (or `queue-consumer` + `internal`), and every SLI
resolves to `success-rate` / `p95-latency`. The frontend RUM gap flagged in earlier passes of this plan
is now resolved by the vendored `faro-web-setup` skill (Faro's auto-instrumentation supersedes the need
for a custom `frontend-page-view.recipe.yaml`) — see [§10](#10-recipe-gaps) for the one narrower,
residual gap (attribute mapping onto Faro's event/measurement model).

## 3. Signal strategy per tier

### 3.1 Tier 1 — Backend (full traces + metrics + logs)

For **every** Tier‑1 backend operation:

- **Traces**:
  - Do not touch the agent-created `{HTTP_METHOD} {http.route}` server span (or `{topic} process` consumer
    span) — it already exists per `http-handler`/`queue-consumer`.
  - Add one manual **`internal`**-recipe child span named exactly `business_span` from `operations.yaml`
    (e.g. `Create Sales Order`, `Reserve Inventory`, `Post Journal Entry`), created via
    `tracer.spanBuilder(name).setSpanKind(SpanKind.INTERNAL).startSpan()` inside the existing trace context
    — **OTel API only**, no new `Tracer`/`SdkTracerProvider` construction.
  - Required attributes on the internal span: `app.operation.name` (= `business_span`),
    `app.operation.type` (`command` for POST/PUT/DELETE, `query` for GET, `event` for Kafka consumers),
    `app.operation.outcome` (`success`|`failure`|`partial` — **never** `app.operation.result`),
    `app.domain` (= `bounded_context`), `app.team` (= `owner`).
  - On failure: `span.setStatus(StatusCode.ERROR)` + `span.recordException(e)` +
    `app.operation.failure_reason` from a per-service controlled vocabulary (never the raw exception message).
  - Downstream calls (`downstream[]` in `operations.yaml`) are already traced by the agent's
    `outbound-http` client-span instrumentation — no manual propagation code needed; only verify the
    W3C `traceparent` header survives the load-balanced `RestClient` / gateway hop (Phase 3a validation item).
- **Metrics**: `{metric_prefix}_total{outcome=...}` counter and `{metric_prefix}_duration_seconds`
  histogram, `histogram_buckets: tier-1` (finer buckets, e.g. 50ms–2s). Derived from the internal span
  via a span-metrics/exemplar pipeline, not hand-rolled counters, so `app.operation.outcome` stays the
  single source of truth for both traces and metrics.
- **Logs**: structured logs at operation entry/exit per [log-schema.md](../../.sre/docs/standards/log-schema.md)
  — `trace_id`/`span_id` injected via the agent's log-correlation appender, guarded by
  `Span.current().getSpanContext().isValid()` so logs emitted with no active span don't fabricate empty IDs.

### 3.2 Tier 2 — Backend + Frontend (traces + key metrics)

- Same `internal`-span pattern as Tier 1, `histogram_buckets: tier-2` (looser thresholds, 2–3s p95).
- Frontend Tier‑2 operations (`view-operations-dashboard`, `view-customer-snapshot`,
  `view-work-in-progress-board`, `view-dispatch-board`, `view-security-audit-log`,
  `view-inventory-by-location`) get a browser RUM span per page/view (see [§5](#5-frontend-instrumentation-greenfield) and [§10](#10-recipe-gaps)),
  plus Core Web Vitals as supporting metrics, not full server-side histograms.
- Metrics: counters + latency histograms as Tier 1, looser SLO targets (0.99 / 0.97 vs 0.999 / 0.99).

### 3.3 Tier 3 — Background (basic metrics + error logs)

- No mandatory manual business span. Rely on the agent's automatic server/DB/HTTP-client spans for
  debugging; add an `internal` span only where a service already has clear failure-classification value
  (e.g. `ingest-mcp-document`, `get-fitment-hint`).
- Metrics: `{metric_prefix}_total{outcome=...}` counter only; no dedicated latency histogram unless a
  `threshold_ms` is cataloged (most Tier‑3 ops have availability-only SLIs).
- Logs: error-level structured logs only, same schema as Tier 1/2, no field reduction.

## 4. Instrumentation guidance — Tier 1 (all 50 backend operations)

Every Tier‑1 backend operation resolves to the same recipe pair: entrypoint span from the agent
(`http-handler` or `queue-consumer`) + manual `internal` business span with the attributes in [§3.1](#31-tier-1--backend-full-traces--metrics--logs).
Grouped by bounded context; `Recipe` column shows the *auto* entrypoint recipe (manual span is always `internal`).

| Bounded context (service) | Operation id | Business span (manual, `internal`) | Entrypoint recipe |
|---|---|---|---|
| order-management (`pos-order`) | create-sales-order-cart | Create Sales Order | http-handler |
| order-management | add-sales-order-item | Add Cart Item | http-handler |
| order-management | update-sales-order-item | Update Cart Item | http-handler |
| order-management | remove-sales-order-item | Remove Cart Item | http-handler |
| order-management | cancel-sales-order | Cancel Sales Order | http-handler |
| order-management | approve-price-override | Approve Price Override | http-handler |
| order-management | reject-price-override | Reject Price Override | http-handler |
| pricing (`pos-price`) | calculate-price-quote | Calculate Price Quote | http-handler |
| pricing | evaluate-price-restrictions | Evaluate Price Restriction | http-handler |
| pricing | override-price-restriction | Override Price Restriction | http-handler |
| pricing | apply-promotion-offer | Apply Promotion Offer | http-handler |
| catalog (`pos-catalog`) | search-products | Search Products | http-handler |
| catalog | resolve-effective-price | Resolve Effective Price | http-handler |
| inventory (`pos-inventory`) | check-inventory-availability | Check Inventory Availability | http-handler |
| inventory | reserve-inventory | Reserve Inventory | http-handler |
| inventory | promote-inventory-allocation | Promote Inventory Allocation | http-handler |
| inventory | release-pick-list | Release Pick List | http-handler |
| inventory | confirm-pick-task | Confirm Pick Task | http-handler |
| inventory | receive-goods | Receive Goods | http-handler |
| inventory | approve-purchase-order | Approve Purchase Order | http-handler |
| inventory | resolve-inventory-shortage | Resolve Inventory Shortage | http-handler |
| accounting (`pos-accounting`) | post-journal-entry | Post Journal Entry | http-handler |
| accounting | create-ap-payment | Create Ap Payment | http-handler |
| accounting | match-vendor-bill | Match Vendor Bill | http-handler |
| accounting | apply-payment | Apply Payment | http-handler |
| accounting | void-payment | Void Payment | http-handler |
| accounting | resolve-gl-mapping | Resolve Gl Mapping | http-handler |
| accounting | reconcile-invoice-event | Reconcile Invoice Event | **queue-consumer** (Kafka `invoice.events.v1`) |
| customer-crm (`pos-customer`) | resolve-account-tier | Resolve Account Tier | http-handler |
| customer-crm | create-party | Create Party | http-handler |
| customer-crm | resolve-party | Resolve Party | http-handler |
| invoicing (`pos-invoice`) | finalize-invoice | Finalize Invoice | http-handler |
| invoicing | capture-payment | Capture Payment | http-handler |
| invoicing | void-invoice-payment | Void Invoice Payment | http-handler |
| invoicing | refund-payment | Refund Payment | http-handler |
| work-order-execution (`pos-workorder`) | create-estimate | Create Estimate | http-handler |
| work-order-execution | approve-estimate | Approve Estimate | http-handler |
| work-order-execution | promote-estimate-to-workorder | Promote Estimate | http-handler |
| work-order-execution | start-workorder | Start Workorder | http-handler |
| work-order-execution | assign-technician | Assign Technician | http-handler |
| work-order-execution | complete-workorder | Complete Workorder | http-handler |
| work-order-execution | generate-workorder-invoice | Generate Workorder Invoice | http-handler |
| work-order-execution | consume-workorder-parts | Consume Workorder Parts | http-handler |
| work-order-execution | start-workorder-labor | Start Workorder Labor | http-handler |
| warranty (`pos-warranty`) | submit-warranty-claim | Submit Warranty Claim | http-handler |
| warranty | decide-warranty-claim | Decide Warranty Claim | http-handler |
| tax (`pos-tax`) | calculate-tax | Calculate Tax | http-handler |
| security-identity (`pos-security-service`) | login-user | Login User | http-handler |
| security-identity | issue-token-pair | Issue Token Pair | http-handler |
| security-identity | refresh-token | Refresh Token | http-handler |
| security-identity | check-authorization-decision | Check Authorization Decision | http-handler |
| gateway (`pos-api-gateway`) | route-gateway-request | Route Gateway Request | http-handler (route `ANY /**` — matched Spring Cloud Gateway route pattern, never the raw incoming path, is what populates `http.route`) |

Per-operation attribute sets, SLIs, and SLOs are already fully specified in `operations.yaml` for all 50
rows above; this table exists to give the instrumentation engineer (Phase 3a) a single checklist. Every
row uses `success-rate` + `p95-latency` SLI recipes except where `operations.yaml` marks
availability-only.

## 5. Frontend instrumentation (greenfield)

### 5.1 Baseline setup

- **Primary path: Grafana Faro Web SDK**, set up via the vendored
  [`faro-web-setup`](../../.sre/opentelemetry-agent-skills/faro-web-setup/SKILL.md) skill (Angular
  `>=14` is supported, and this repo runs Angular 21). Faro wraps the OTel Web SDK, so
  `getWebInstrumentations()` gives automatic page-view/navigation spans (via its bundled
  `TracingInstrumentation`), Core Web Vitals, JS error capture, and network (fetch/XHR)
  instrumentation from one init call — this satisfies `agent: otel-web-sdk` in `operations.yaml`
  without hand-assembling the individual `@opentelemetry/sdk-trace-web` /
  `@opentelemetry/instrumentation-fetch` / `@opentelemetry/context-zone` packages. Follow the skill's
  Step 0–2 (framework auto-detection → collector URL → base implementation from its Angular section
  in `frameworks.md`) to generate the init file and wire it as the first import in `main.ts`.
- Root span per Angular route navigation comes from Faro's auto-navigation tracking (router
  integration where available, or `experimental: { trackNavigation: true }` when no router hook is
  wired) — not a hand-rolled route-change listener; child spans for each `ApiBaseService` HTTP call
  are produced automatically by the same `TracingInstrumentation`'s fetch/XHR instrumentation.
- OTLP export target: point the skill's Step 1 collector URL at a Faro-compatible receiver in front of
  the same destination as `otel_destination` in `sre.config.yaml` (currently `local`,
  `http://localhost:4318` — e.g. Grafana Alloy's `faro.receiver`, rather than pointing Faro directly
  at the raw OTLP/HTTP port). CORS on the collector/gateway must allow the frontend origin —
  see `.sre/docs/standards/instrumentation-troubleshooting.md` "CORS Blocking Browser → Backend" before
  wiring this up in non-local environments.

### 5.2 Signal strategy for the 8 cataloged frontend operations

| Operation id | Tier | Business span | Recipe | Notes |
|---|---|---|---|---|
| view-operations-dashboard | 2 | View Operations Dashboard | Faro auto page-view span (`getWebInstrumentations`) + `pushEvent` | Correlates to `submit-nlti-request` downstream |
| view-customer-snapshot | 2 | View Customer Snapshot | Faro auto page-view span + `pushEvent` | Correlates to `resolve-party` (pos-customer) |
| view-work-in-progress-board | 2 | View Work In Progress Board | Faro auto page-view span + `pushEvent` | Correlates to pos-workorder, pos-people |
| view-dispatch-board | 2 | View Dispatch Board | Faro auto page-view span + `pushEvent` | Correlates to pos-shop-manager |
| view-security-audit-log | 2 | View Security Audit Log | Faro auto page-view span + `pushEvent` | Correlates to pos-security-service |
| view-inventory-by-location | 2 | View Inventory By Location | Faro auto page-view span + `pushEvent` | Correlates to `check-inventory-availability` |
| complete-bulk-import-job | 3 | Complete Bulk Import Job | Faro auto page-view span + `pushEvent`/`pushMeasurement` (multi-step wizard, one event per step) | Availability-only SLI |
| view-timekeeping-discrepancy-report | 3 | View Timekeeping Discrepancy Report | Faro auto page-view span + `pushEvent` | Availability-only SLI |

All 8 carry `app.operation.name`/`app.operation.type`/`app.operation.outcome` two ways: (a) as span
attributes on Faro's auto page-view span (accessible via the OTel `TracerProvider` Faro installs, same
taxonomy as backend `internal` spans — [§7](#7-attribute-taxonomy-validation)), and (b) as attributes
on a companion `faro.api.pushEvent('feature_used', { 'app.operation.name': ..., 'app.operation.outcome': ... })`
call fired at the same point in the component lifecycle, so the trace view and the event view in
Grafana agree. `app.operation.outcome` is `success` when the page/view renders without a client-side
or 5xx error, `failure` otherwise — identical semantics to the backend, so success-rate SLIs compute
identically across frontend and backend. Core Web Vitals (LCP/CLS/INP) arrive automatically as Faro
measurements and feed Tier assessment (see [§10](#10-recipe-gaps) for the attribute-mapping detail).

### 5.3 Frontend Tier‑1 coverage (by correlation)

`operations.yaml` intentionally does **not** duplicate a frontend operation for flows already covered by
a Tier‑1 backend operation (see the "Frontend-driven operations" header comment in the file). Tier‑1
coverage on the frontend is achieved by:

1. Instrumenting the Angular components/services on the critical paths below with a RUM span per
   user action (not a new cataloged operation, just a span), named to match the backend `business_span`
   it drives so trace search is consistent:

   | Frontend route | Backend Tier‑1 operation invoked | RUM span name |
   |---|---|---|
   | `/app/order/cart` | create-sales-order-cart, add-sales-order-item | Create Sales Order, Add Cart Item |
   | `/app/order/{orderId}/price-override/{lineId}` | approve-price-override / reject-price-override | Approve Price Override / Reject Price Override |
   | `/app/order/{orderId}/cancel` | cancel-sales-order | Cancel Sales Order |
   | `/app/billing/invoices/{invoiceId}` | finalize-invoice | Finalize Invoice |
   | `/app/billing/invoices/{invoiceId}/payment-capture` | capture-payment | Capture Payment |
   | `/app/workexec/estimates/new` | create-estimate | Create Estimate |
   | `/app/workexec/estimates/{estimateId}/approval/*` | approve-estimate | Approve Estimate |
   | `/app/workexec/workorders/{workorderId}/finalize` | complete-workorder | Complete Workorder |
   | `/app/workexec/workorders/{workorderId}/invoice-finalization` | generate-workorder-invoice, finalize-invoice | Generate Workorder Invoice, Finalize Invoice |

2. Relying on Faro's built-in `TracingInstrumentation` to attach the W3C `traceparent` header to every
   `ApiBaseService` call automatically for same-origin requests (already covered by the base setup in
   [§5.1](#51-baseline-setup)); if a Tier‑1 route calls a cross-origin API (e.g. a CDN-fronted gateway
   domain), add that origin to `propagateTraceHeaderCorsUrls` on the `TracingInstrumentation` config
   (`faro-web-setup` skill, `advanced.md` §A) so the browser span still becomes the trace root and the
   backend's agent-created `http-handler` span (and its nested `internal` business span) becomes a
   child — no manual header-propagation code needed, this is Faro's correlation mechanism, not a new
   signal type.
3. These flows do **not** get a separate SLI/SLO in `operations.yaml` — they inherit the backend
   operation's SLO. The RUM span's `app.operation.outcome` is an additional (client-side) success signal,
   useful for detecting frontend-only failures (e.g. a rendering error after a successful API call) that
   the backend SLO would not catch.

## 6. Correlation model

- **Trace propagation path**: Browser (Faro Web SDK, wrapping the OTel Web SDK) → `ApiBaseService`
  (fetch/XHR auto-instrumented by Faro's `TracingInstrumentation`, `traceparent` header attached) →
  `pos-api-gateway` (agent propagates, adds `X-Authorities`/`X-User`/
  `X-User-Id` from the JWT, does **not** touch `traceparent`) → downstream `pos-*` service (agent
  continues the trace) → Kafka domain event (agent's Kafka producer instrumentation injects trace context
  into message headers; consumer's `reconcile-*` `queue-consumer` span continues it) → GL posting /
  reconciliation.
- **Baggage**: only `correlation.request_id` (per
  [baggage-taxonomy.md](../../.sre/docs/standards/baggage-taxonomy.md)) may be set, at the gateway or
  frontend, as an idempotency key for multi-step flows (e.g. bulk-import wizard, estimate-to-invoice).
  Never put `enduser.id`/`enduser.email` in baggage — those stay as span/log attributes only.
- **Log correlation**: `trace_id`/`span_id` injected by the agent's logging bridge on the backend;
  on the frontend, RUM spans write `trace_id`/`span_id` into any client-side structured logs (e.g. Faro
  session events) using the same field names as the backend log schema. Guard with
  `Span.current().getSpanContext().isValid()` (or the Web SDK equivalent) so logs outside an active span
  don't emit garbage IDs.
- **Metric exemplars**: histogram buckets for `{metric_prefix}_duration_seconds` should carry exemplars
  pointing at a sampled trace ID, so a p95 spike on a Tier‑1 dashboard panel links directly to a stored
  trace — required for Tier 1, recommended for Tier 2.

## 7. Attribute taxonomy validation

All attributes proposed above are already approved namespaces in
[attribute-taxonomy.md](../../.sre/docs/standards/attribute-taxonomy.md):

| Attribute | Namespace | Cardinality | Used on |
|---|---|---|---|
| `app.operation.name` | `app.operation.*` | medium | every `internal` business span (backend + frontend) |
| `app.operation.type` | `app.operation.*` | low | every business span |
| `app.operation.outcome` | `app.operation.*` | low | every business span — **`outcome`, never `result`**, per constraint |
| `app.operation.failure_reason` | `app.operation.*` | medium | failure paths, controlled vocabulary per service |
| `app.domain` | `app.*` | low | = `bounded_context` |
| `app.team` | `app.*` | low | = `owner` |
| `http.request.method`, `http.route`, `http.response.status_code` | `http.*` | low/medium | agent-created server spans (no manual work) |
| `messaging.system`, `messaging.destination.name`, `messaging.operation.type` | `messaging.*` | low/medium | agent-created Kafka spans (`reconcile-invoice-event`, `reconcile-workorder-manifest`) |
| `db.system`, `db.operation.name`, `db.collection.name` | `db.*` | low/medium | agent-created DB spans (no manual work) |
| `enduser.id`, `enduser.role` | `enduser.*` (governed) | high/low | span/log attribute only on auth-sensitive ops (`login-user`, `check-authorization-decision`) — never a metric label |

No new namespace is required. Frontend RUM spans reuse `app.operation.*` and `http.route` (Angular route
path, parameterized, e.g. `/app/crm/crm-snapshot/{partyId}`) rather than inventing a browser-specific
namespace — this keeps cross-surface queries (`app.operation.outcome="failure"` across backend and
frontend) consistent.

**Forbidden attributes reminder** (per taxonomy): no `db.statement`, no `url.full`/`url.query`, no raw
`http.request.body`/`http.response.body`, no `user.id`/`user.email`/`session.id`. These apply equally to
frontend RUM spans, where the temptation to log the full route with query string is highest.

## 8. Sampling strategy

Per [sampling-strategy.md](../../.sre/docs/standards/sampling-strategy.md), tail-based sampling is
configured at the OTel Collector, applied uniformly across backend and frontend signals reaching it:

| Rule | Applies to | Sampling % |
|---|---|---|
| Always sample errors (`status=ERROR`) | All tiers, backend + frontend | 100% |
| Always sample `app.operation.outcome in [failure, partial]` | All tiers, backend + frontend | 100% |
| Always sample latency SLO violations (per-tier `threshold_ms`) | Tier 1: 500ms–2000ms depending on op; Tier 2: 3000ms; Tier 3: no latency SLI, skip | 100% |
| Baseline probabilistic sample of remaining success traces | Tier 1 | ≥10% |
| Baseline probabilistic sample | Tier 2 | 5–10% |
| Baseline probabilistic sample | Tier 3 | 1%, metrics-only acceptable |

Tier‑1 error traces are never dropped, on either surface — this includes the frontend RUM spans that
wrap Tier‑1 backend calls (order cart, invoice finalize/pay, estimate approval, per [§5.3](#53-frontend-tier1-coverage-by-correlation)),
since a client-side failure on a Tier‑1 flow is exactly as important as a backend one.

## 9. GenAI observability

`pos-mcp-server` is the only GenAI-scoped service (LangChain4j + Ollama, per `sre.config.yaml`
`context.genai_providers` and `docs/sre-todo.md`). This section acknowledges GenAI as a signal category;
full design is Phase 2g (GenAI Observability Assistant) / Phase 3g (GenAI Instrumentation Engineer) work
against `.sre/packs/recipes/genai/genai-implementation-guide.md`.

1. **Model span strategy** — `submit-nlti-request` (Tier 2, http-handler entry) and `stream-mcp-chat`
   (Tier 2, http-handler entry, streaming) will each get a nested `gen_ai.operation.name=chat` span using
   the `ai-llm-call` recipe; `ingest-mcp-document` (Tier 3) will use `ai-rag-retrieval` for the ingestion/
   embedding path. Any tool invocation from the NLTI flow uses `ai-tool-execution`
   (`gen_ai.operation.name=execute_tool`); any multi-step agent orchestration uses `ai-invoke-agent`.
2. **Token usage metrics** — `gen_ai.client.token.usage` histogram (input/output tokens) per
   `gen_ai.request.model` × `gen_ai.operation.name`, per the genai recipe's Contract 5. Ollama
   self-hosted models still populate `gen_ai.request.model`/`gen_ai.response.model` even with zero
   marginal cost.
3. **Cost attribution** — cost-per-call metric keyed by `gen_ai.request.model` + `app.operation.name`
   (`submit-nlti-request` / `stream-mcp-chat` / `ingest-mcp-document`), feeding the GenAI Cost Guardian.
   Ollama's cost model is non-standard (self-hosted + a `gpt-oss:120b` cloud fallback per
   `docker-compose.yml`) — flagged in `docs/sre-todo.md` for Phase 2g/4 to resolve.
4. **Quality/evaluation signals** — latency and error rate ride on the standard `success-rate`/
   `p95-latency` SLIs already cataloged for these 3 operations; a `gen_ai.evaluation.result` event
   (relevance/groundedness scoring) is deferred to Phase 2g.
5. **PII gating** — NLTI requests/responses and RAG document content **must not** be written to span
   attributes or logs without redaction. The existing `Span.current()` correlation-id attachment in
   `NltiRequestServiceImpl` (flagged in `docs/sre-todo.md`/brownfield-assessment.md) attaches only
   correlation/user-id attributes today — no prompt/completion content — and must stay that way until a
   redactor profile is defined in Phase 2g.
6. **Sampling** — `submit-nlti-request` and `stream-mcp-chat` are Tier 2 (5–10% baseline, 100% on
   error/failure per [§8](#8-sampling-strategy)); no Tier‑1 GenAI operation currently exists, so the
   "never drop Tier‑1 error traces" rule does not yet apply here, but must be re-checked if a GenAI
   operation is promoted to Tier 1.
7. **Downstream agents required** — Phase 2g (GenAI Observability Assistant) and Phase 3g (GenAI
   Instrumentation Engineer) are both required for this service before GenAI telemetry ships; this plan
   does not substitute for either.

## 10. Recipe gaps

The frontend RUM gap flagged in earlier passes of this plan is now resolved by a vendored skill rather
than a proposed custom recipe. One narrower, genuinely new gap remains and is resolved below using the
existing `app.operation.*` taxonomy — nothing in this section is left unresolved.

### 10.1 Frontend RUM instrumentation — resolved by `faro-web-setup`

`.sre/opentelemetry-agent-skills/faro-web-setup/SKILL.md` (vendored from the official Grafana Faro Web
SDK skill, Apache-2.0, Angular `>=14` supported) supersedes the need to invent a custom RUM span shape
or author a new `frontend-page-view.recipe.yaml`. It provides an authoritative, ready-to-run
instrumentation pattern for all 8 cataloged frontend operations in
[§5.2](#52-signal-strategy-for-the-8-cataloged-frontend-operations) and the Tier‑1 RUM correlation
spans in [§5.3](#53-frontend-tier1-coverage-by-correlation):

- **Page/route-level spans**: Faro's `getWebInstrumentations()` bundles `TracingInstrumentation`, which
  auto-creates a span per route navigation (via router integration, or `experimental.trackNavigation`
  when no router hook exists) plus a child span for every fetch/XHR call — no custom
  `kind: internal`-equivalent browser span needs to be hand-authored; Faro's auto-instrumentation *is*
  the recipe.
- **Custom business signals**: `faro.api.pushEvent(name, attributes)` for discrete business events
  (e.g. a `feature_used` event per cataloged operation) and `faro.api.pushMeasurement({ type, values })`
  for numeric business/Core-Web-Vitals measurements — both documented in the skill (`SKILL.md` Step 7,
  `advanced.md`).
- **Backend correlation**: `propagateTraceHeaderCorsUrls` on `TracingInstrumentation` (skill
  `advanced.md` §A) handles cross-origin trace-header propagation; same-origin is automatic — see the
  updated [§5.1](#51-baseline-setup) and [§5.3](#53-frontend-tier1-coverage-by-correlation).
- **No new span recipe required.** `frontend-page-view.recipe.yaml` (proposed in an earlier pass of
  this plan) is no longer needed — the skill is now the authoritative, in-repo source for this shape,
  so there is nothing left for the framework maintainers to author or for Phase 3b to re-derive.

### 10.2 Residual gap — `app.operation.*` mapping onto Faro's event/measurement model

The skill documents *how* to call `pushEvent`/`pushMeasurement`, but not *which attribute keys* to use
— that mapping is specific to this repo's taxonomy, not a generic Faro concern. Resolved as follows,
using the existing [§7](#7-attribute-taxonomy-validation) conventions rather than inventing a new
namespace:

- **Page-view spans** (Faro's auto navigation span): set `app.operation.name` (= the frontend
  operation's `business_span`), `app.operation.type` (`ui_view` for read-only pages, `ui_action` for
  form/mutation flows — a frontend-only extension of `app.operation.type`, still within the approved
  `app.operation.*` namespace), and `app.operation.outcome` as span attributes via the OTel
  `TracerProvider` Faro installs (same attribute-setting API as any OTel Web SDK span) — this keeps
  parity with backend `internal` spans so `app.operation.outcome="failure"` queries work identically
  across both surfaces.
- **`pushEvent` calls**: pass `app.operation.name`, `app.operation.type`, and `app.operation.outcome`
  in the event's `attributes` argument, e.g.
  `faro.api.pushEvent('feature_used', { 'app.operation.name': 'View Operations Dashboard', 'app.operation.type': 'ui_view', 'app.operation.outcome': 'success' })`,
  so the event view in Grafana Frontend Observability carries the same taxonomy as the span view.
- **`pushMeasurement` calls**: the skill's documented signature (`{ type, values }`) has no attribute
  channel, so `app.operation.*` cannot be attached directly to a measurement. Resolve this by encoding
  the operation identity in the measurement `type` string itself (e.g.
  `type: 'view-operations-dashboard.web-vitals'` rather than a bare `'web-vitals'`), and rely on the
  enclosing page-view span (which does carry `app.operation.name`/`app.operation.outcome`) for
  outcome correlation via trace-context/timestamp join — measurements are never left unattributed.
- No new attribute namespace is introduced; `app.operation.type` values `ui_view`/`ui_action` are the
  only frontend-specific extension, consistent with how [§7](#7-attribute-taxonomy-validation) already
  scopes `app.operation.type` as a controlled, low-cardinality enum.
