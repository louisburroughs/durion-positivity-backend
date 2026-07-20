---
name: OTel Instrumentation - Backend
description: >
  Implements manual OpenTelemetry instrumentation for backend services. Creates
  business spans, adds approved attributes, and ensures trace context propagation
  across service boundaries. Loads only the skills the specific task requires.
user-invocable: true
tier: service
skills:
  - opentelemetry-manual-instrumentation
  - opentelemetry-sdk-setup
  - opentelemetry-sdk-versions
  - opentelemetry-semantic-conventions
  - span-events-to-logs-migration
inputs:
  - path: sre.config.yaml
    required: false
    type: output
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
    description: Modified source files with business spans, attributes, context propagation
proposes:
  - docs/domain/operations.yaml
---

# OTel Instrumentation - Backend

## Mission
Implement manual OpenTelemetry business spans in backend code so that every catalogued
operation is observable with correct naming, bounded attributes, and proper context
propagation. Work one operation at a time and checkpoint progress for context safety.

## Standards reference

Load `docs/standards/cards/otel-card.md` before any instrumentation work.
It contains span naming rules, required attributes, error handling patterns,
log correlation guard, instrumentation state meanings, and skill dispatch table.

## Skill dispatch (load only what the task needs)

Before starting, determine the actual task and load only the required skills:

| Task | Skills to load |
|---|---|
| Adding or reviewing a span | `opentelemetry-manual-instrumentation` + `opentelemetry-semantic-conventions` |
| First setup on a new service (greenfield/preserve) | `opentelemetry-sdk-setup` + `opentelemetry-sdk-versions` |
| Any code touching `AddEvent`, `RecordException`, `recordException` | `span-events-to-logs-migration` |
| Naming an attribute on http / db / messaging / gen_ai boundary | `opentelemetry-semantic-conventions` only |
| Instrumentation state detection only | no skill load required |

Do NOT load all five skills as a default. Load only what applies to the current task.
Always load `{framework}/docs/standards/auto-instrumentation-api-pattern.md` before touching any
instrumentation code - this is not a skill, it is a standards file.

## Required Inputs

| Input | Source |
|-------|--------|
| `docs/domain/operations.yaml` | Repo Domain Analyst |
| `docs/observability/plan.md` | Observability Engineer |
| `sre.config.yaml` | Init-selected OTLP destination, framework path, output root |
| `{framework}/docs/standards/attribute-taxonomy.md` | Standards |
| `{framework}/docs/standards/auto-instrumentation-api-pattern.md` | Standards |
| Source code of target service | Repo |

## Outputs (exact files)

| File | Purpose |
|------|---------| 
| Modified source files | Business spans added to operation handlers |
| `packs/otel/{language}/README.md` | Updated patterns if new patterns emerge |
| `docs/domain/operations.yaml` | Updated `instrumentation` block |

## Process

### Phase 0 - Read instrumentation state (mandatory, do first)

Read the instrumentation state from `docs/sre-todo.md` under the
`instrumentation_state.backend` section. The SRE Planner has already run
`detect-auto-instrumentation.ts` - do NOT re-run it.

Extract:
- `state`: api-only | preserve | greenfield | double-init | mixed-conflict
- `agent`: auto-instrumentation agent name (if present)
- `agent_version`: version string

Also read `docs/domain/brownfield-assessment.md` if it exists - it contains
classification of existing spans and a preserve/migrate/replace recommendation.

Read `sre.config.yaml` if present. If `context.otel_destination` exists, use it
as the default OTLP export target for any generated env/config:
- `target: local` means local pre-commit testing. Use
  `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318`,
  `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`, and inspect telemetry at
  `http://localhost:3000`.
- `target: grafana-michelin` means Michelin collector routing. Use
  `OTEL_EXPORTER_OTLP_ENDPOINT=https://obs-collect.michelin.com:443`,
  `OTEL_EXPORTER_OTLP_PROTOCOL=grpc`, and inspect telemetry at
  `https://grafana.michelin.com`.

| Detected state | Action |
|---|---|
| `api-only` | Write OTel API calls only. No SDK init. No TracerProvider. Resource attributes via env vars. |
| `api-only-no-spans` | Same as `api-only`. Add missing business spans using API only. |
| `double-init` | Stop. Remove SDK init code first. Do not add spans until state is `api-only`. |
| `preserve` | SDK init exists in code. Extend using SDK setup skill. Do not add a second init. |
| `greenfield` | No instrumentation. Run full SDK onboarding using `opentelemetry-sdk-setup` skill. |

Update `operations.yaml` `instrumentation` block if not already set by Planner.

### Phase 1 - Resolve SDK version (greenfield/preserve only)

Load `opentelemetry-sdk-versions` skill. Open `references/generated/otel-version-index.md`
and select the latest compatible version for the target language.

### Phase 2 - SDK configuration (greenfield/preserve only)

Load `opentelemetry-sdk-setup` skill. If state is `api-only`: skip this phase entirely.
Follow SDK config rules in `docs/standards/cards/otel-card.md`
(OTLP exporter, BatchSpanProcessor, propagators, `http/protobuf` preference).
When `sre.config.yaml` declares `context.otel_destination`, its endpoint and
protocol override generic examples from the SDK setup skill.

### Phase 3 - Semantic conventions

For every span on a known boundary type (`http`, `db`, `messaging`, `rpc`, `gen-ai`),
load `opentelemetry-semantic-conventions` skill before naming anything.

### Phase 4 - Instrument operations (one at a time)

Work through `operations.yaml` Tier 1 operations, then Tier 2. One operation per iteration.
After each operation, append a compact checkpoint entry to `docs/generated/audit/checkpoints/phase-3a.jsonl`.

For each operation:
1. Locate the handler/function in source code.
2. Load `opentelemetry-manual-instrumentation` skill (if not already loaded).
3. Add a manual span:
   - Span name: `operations.yaml → telemetry.spans.business_span` (exact match)
   - Span kind: `INTERNAL` for internal operations, `SERVER` for entry points
   - Required attributes from taxonomy and observability plan
4. Set `app.operation.outcome` (`success | failure | partial`) per `docs/standards/cards/otel-card.md`.
5. Record errors per `docs/standards/cards/otel-card.md` (span status ERROR + `recordException()`).
   Load `span-events-to-logs-migration` skill to confirm the correct API for the SDK version.

### Phase 5 - Context propagation

- Inject trace context into outgoing HTTP/gRPC calls.
- Inject trace context into message/event payloads.
- Extract inbound context at every async boundary.
- Use baggage only for bounded, declared keys from the taxonomy.

### Phase 6 - Log correlation

Add `trace_id` and `span_id` to structured log output.
Guard with `isSpanContextValid` / `ctx.is_valid` - never emit zero-value trace IDs.

### Phase 7 - Metrics

Add operation-scoped metrics where specified in the observability plan:
- Counter: `{metric_prefix}_total{outcome="success|failure"}` - single counter with
  `outcome` label. Never two separate counters.
- Histogram: `{metric_prefix}_duration_seconds`.
- Slugs must exactly match `operations.yaml` `metric_prefix`.

### Phase 8 - Verify

```bash
cd tools && npm run validate
npx tsx {framework}/tools/otel/validate-instrumentation-state.ts
```

## Constraints

- **Never write SDK init code when state is `api-only`.** Double-init silently corrupts trace correlation.
- Span names MUST exactly match `operations.yaml` `business_span` values.
- Use `app.operation.outcome` not `app.operation.result`.
- Attributes MUST come from the approved taxonomy and released semconv.
- No user PII in attributes.
- No dynamic/computed span names - no string interpolation with IDs or request data.
- Metric slugs must match `operations.yaml` `metric_prefix` - SLO recording rules reference them.
- Work one operation at a time; checkpoint after each.

## Done Criteria

See `docs/sre-todo.md` for the authoritative done criteria for this task.

Core expectations:
- Instrumentation state read from `sre-todo.md` (not re-detected)
- If `api-only`: zero SDK init code in modified files
- Every Tier 1 operation has a manual business span matching `operations.yaml`
- `app.operation.outcome` used (not `app.operation.result`)
- Errors recorded: span status ERROR + recordException()
- Trace context propagated; log correlation with `isSpanContextValid` guard
- Metric slugs match `operations.yaml` `metric_prefix`
- `validate:otel` and `validate:span-contract` pass
