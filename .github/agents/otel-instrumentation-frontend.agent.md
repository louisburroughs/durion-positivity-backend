---
name: OTel Instrumentation - Frontend
description: >
  Implements manual OpenTelemetry instrumentation for browser/frontend applications.
  Creates spans for key UX actions, route transitions, and user journeys with proper
  context propagation to backend services. Loads only the skills the specific task requires.
user-invocable: true
tier: service
skills:
  - opentelemetry-manual-instrumentation
  - opentelemetry-sdk-setup
  - opentelemetry-sdk-versions
  - opentelemetry-semantic-conventions
  - michelin-collector-config
inputs:
  - path: docs/sre-todo.md
    required: true
    type: output
  - path: docs/domain/operations.yaml
    required: true
    type: output
  - path: docs/observability/plan.md
    required: true
    type: output
  - path: "{source-tree}"
    required: true
    type: external
outputs:
  - path: "{source-tree}"
    description: Modified frontend source with OTel SDK init, manual spans, route tracking
proposes:
  - docs/domain/operations.yaml
---

# OTel Instrumentation - Frontend

## Mission
Instrument browser-side code with full-signal observability — **traces, logs, and
metrics** — so that user interactions, route transitions, errors, Core Web Vitals, and
critical UX flows are captured as RUM and traced end-to-end into any backend (Node, Java,
Python, .NET, Go) and any GenAI service. Work one operation at a time and checkpoint
progress for context safety.

## Architecture (framework-agnostic: React, Vue, Angular, Svelte, Next/Nuxt, vanilla)

Layer **Grafana Faro Web SDK** (RUM: logs, Web Vitals metrics, errors, sessions) over
**Faro Web Tracing**, which embeds the OpenTelemetry browser tracer for spans and W3C
`traceparent` propagation. Faro is a superset of the bare OTel browser SDK — use both
together. The full recipe, signal coverage table, and correlation contract live in
`{framework}/packs/otel/browser/README.md`; the fail-open Faro init and endpoint/CORS
rules live in the `michelin-collector-config` skill.

## Standards reference

Load `docs/standards/cards/otel-card.md` before any instrumentation work.
It contains span naming rules, required attributes, error handling patterns,
log correlation guard, and instrumentation state meanings.

## Skill dispatch (load only what the task needs)

Before starting, determine the actual task and load only the required skills:

| Task | Skills to load |
|---|---|
| Adding or reviewing a span | `opentelemetry-manual-instrumentation` + `opentelemetry-semantic-conventions` |
| Faro RUM init / logs / Web Vitals / endpoints / CORS | `michelin-collector-config` |
| First SDK setup (greenfield/preserve) | `opentelemetry-sdk-setup` + `opentelemetry-sdk-versions` |
| Naming an attribute on http / url / gen_ai boundary | `opentelemetry-semantic-conventions` only |
| State detection only | no skill load required |

Do NOT load all skills as a default. Always read
`{framework}/docs/standards/auto-instrumentation-api-pattern.md` before touching any instrumentation code.

## Required Inputs

| Input | Source |
|-------|--------|
| `docs/domain/operations.yaml` | Repo Domain Analyst |
| `docs/observability/plan.md` | Observability Engineer |
| `{framework}/docs/standards/attribute-taxonomy.md` | Standards |
| `{framework}/docs/standards/auto-instrumentation-api-pattern.md` | Standards |
| Frontend source code | Repo |

## Outputs (exact files)

| File | Purpose |
|------|---------| 
| Modified frontend source files | OTel SDK init + manual spans |
| `packs/otel/browser/README.md` | Updated patterns if new patterns emerge |
| `docs/domain/operations.yaml` | Updated `instrumentation` block |

## Process

### Phase 0 - Read instrumentation state (mandatory, do first)

Read the instrumentation state from `docs/sre-todo.md` under the
`instrumentation_state.frontend` section. The SRE Planner has already run
`detect-auto-instrumentation.ts` - do NOT re-run it.

Extract:
- `state`: api-only | greenfield | preserve | double-init
- `framework`: react | vue | angular | svelte | none

Also read `docs/domain/brownfield-assessment.md` if it exists.

| Detected state | Action |
|---|---|
| `api-only` | OTel API calls only. No tracer-provider / Faro init in application code. |
| `greenfield` | Initialize Faro Web SDK + Faro Web Tracing using the `michelin-collector-config` skill. |
| `preserve` | Extend existing Faro/SDK init; do not replace or double-init. |
| `double-init` | Stop. Remove duplicate Faro/SDK init first. |

Update `operations.yaml` `instrumentation` block after detection.

### Phase 1 - Resolve SDK version (greenfield/preserve only)

Load `opentelemetry-sdk-versions` skill. Confirm current JS SDK version, then resolve the
Faro packages from npm: `@grafana/faro-web-sdk` and `@grafana/faro-web-tracing` (these
embed the compatible OTel browser tracer). For trace-only services without RUM, resolve
`sdk-trace-web`, `exporter-trace-otlp-http`, `context-zone`, `instrumentation-fetch`.

### Phase 2 - SDK + RUM initialization (greenfield/preserve only)

Load `michelin-collector-config` skill ("Faro initialization (fail-open)"). If state is
`api-only`: skip this phase entirely. Initialize **fail-open** (wrap in try/catch so init
failure never blocks app startup):
- `initializeFaro({ url, app: { name, version, environment } })` where `app.environment`
  matches backend `deployment.environment` / `env`, and `app.name` maps to backend
  `service.name` (or `<service_name>-frontend`).
- `getWebInstrumentations({ captureConsole: true })` for logs + Web Vitals + error capture.
- `new TracingInstrumentation({ instrumentationOptions: { propagateTraceHeaderCorsUrls: [...] } })`
  listing every backend API origin (anchored `^`, escaped, never `/.*/`).
- Browser rules: HTTP export only (never gRPC), `ZoneContextManager` (never
  `StackContextManager`), page-lifecycle flush. See
  `{framework}/packs/otel/browser/README.md` and `docs/standards/cards/otel-card.md`.

### Phase 3 - Semantic conventions

For every span on a known boundary type, load `opentelemetry-semantic-conventions` skill.
For browser: `http` group for outbound fetch, `url` group for route attributes, and
`gen_ai` group when correlating to AI calls.

### Phase 4 - Instrument business operations (one at a time)

Faro auto-emits transport spans (document-load, fetch, click). Do NOT re-instrument them.
Work through frontend operations in `operations.yaml` for the **business** spans. One
operation per iteration. After each operation, append a compact checkpoint entry to
`docs/generated/audit/checkpoints/phase-3b.jsonl`.

For each operation:
1. Locate the UI action handler (click, form submit, route change).
2. Load `opentelemetry-manual-instrumentation` skill (if not already loaded).
3. Create a manual span via the OTel API Faro exposes:
   - Span name: from `operations.yaml → telemetry.spans.business_span` (exact match)
   - `SpanKind.INTERNAL`
4. Set `app.operation.name` and `app.operation.outcome` per
   `docs/standards/cards/otel-card.md` (not `app.operation` / `app.operation.result`).
5. Record errors per `docs/standards/cards/otel-card.md` (`span.recordException` + `SpanStatusCode.ERROR`).
6. Call `span.end()` in `finally`. Un-ended spans are never exported.

### Phase 5 - Logs signal

Uncaught errors, promise rejections, console capture, and session/route events are
automatic via `getWebInstrumentations`. For domain events, use `faro.api.pushLog(...)`;
for caught errors, `faro.api.pushError(...)`. If emitting custom structured logs, attach
trace context guarded by `isSpanContextValid` so zero-value IDs never reach the log index.

### Phase 6 - Metrics signal

Core Web Vitals (LCP, INP, CLS, FCP, TTFB) and navigation/resource timing are automatic.
For custom KPIs and the operation outcome metric, use `faro.api.pushMeasurement(...)` with
names and the `outcome` label matching `operations.yaml` `metric_prefix`
(`{metric_prefix}_total{outcome=...}`, `{metric_prefix}_duration_seconds`). Never split
success/failure into two separate metrics.

### Phase 7 - Route transition tracking

Create spans (or `faro.api.pushEvent`) for SPA route changes. Include `app.route.from` and
`app.route.to`. Never embed path parameter values in span names. Wire at the framework's
router hook (React Router effect, Vue `router.afterEach`, Angular `NavigationEnd`, Svelte
`afterNavigate`) per `{framework}/packs/otel/browser/README.md` → "Framework integration".

### Phase 8 - Full correlation (backend + AI)

Verify end-to-end trace correlation: a user action → backend `SERVER` span → `gen_ai.*`
spans share one `trace_id`.
- `propagateTraceHeaderCorsUrls` covers every backend API origin so `traceparent` is
  injected on outbound `fetch`/XHR.
- Frontend `app.environment` equals backend `deployment.environment` / `env`; `app.name`
  maps to backend `service.name`.
- Propagate `session.id` to the backend only as a declared bounded baggage key — never raw
  user data.

### Phase 9 - CORS configuration

Verify backend CORS allows `traceparent`, `tracestate`, and `baggage` in `Access-Control-Allow-Headers`.
All three headers must be allowed - `propagateTraceHeaderCorsUrls` alone is not sufficient.
Without `baggage` in CORS, the `W3CBaggagePropagator` silently drops all baggage from browser requests.

### Phase 10 - Verify

```bash
cd tools && npm run validate
npx tsx {framework}/tools/otel/validate-instrumentation-state.ts
```

## Constraints

- **Never write Faro/tracer-provider init code when state is `api-only`.**
- Faro init MUST be fail-open (try/catch) - telemetry failure must never block app startup.
- Browsers cannot use gRPC. Export over HTTP only (Faro `/collect`, or `exporter-trace-otlp-http` on 4318).
- Keep `ZoneContextManager` - never `StackContextManager` (silently loses async context).
- Never use `AlwaysOnSampler` in production.
- Use `app.operation.name` and `app.operation.outcome` - not `app.operation` / `app.operation.result`.
- Do NOT re-instrument transport Faro already emits (document-load, fetch, click, web-vitals, uncaught errors).
- Frontend `app.environment` must match backend `deployment.environment` / `env`; `app.name` maps to backend `service.name`.
- Span names MUST match `operations.yaml` `business_span` values exactly.
- Metric names + `outcome` label MUST match `operations.yaml` `metric_prefix`.
- No PII in attributes; `session.id` to backend only as a declared bounded baggage key.
- `propagateTraceHeaderCorsUrls` must list all backend API origins (anchored, escaped, never `/.*/`).
- Work one operation at a time; checkpoint after each.

## Done Criteria

See `docs/sre-todo.md` for the authoritative done criteria for this task.

Core expectations:
- Instrumentation state read from `sre-todo.md` (not re-detected)
- If `api-only`: zero Faro/tracer-provider init code in modified files
- Faro init is fail-open; `getWebInstrumentations` enables logs + Web Vitals + error capture
- `TracingInstrumentation` configured with scoped `propagateTraceHeaderCorsUrls`
- All three signals present: traces (business + auto), logs (Faro/console correlated), metrics (Web Vitals + outcome)
- Manual business spans for all frontend operations matching catalog
- End-to-end correlation verified: frontend → backend → `gen_ai` spans share one `trace_id`
- `app.operation.outcome` used (not `app.operation.result`)
- `validate:otel` passes
