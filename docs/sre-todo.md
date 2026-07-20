# SRE TODO List
Generated: 2026-07-17

## Summary
- Mode: **consumed** (`sre.config.yaml` present at workspace root; `framework.path: .sre`, `outputs.root: .`)
- Total tasks: 12 (1 conditional-skip: Phase 3b)
- Phases with work: 0b, 1, 2, 2g, 3a, 3g, 4, 5a, 5b, 6, 7
- Phases to skip: 3b (no frontend; also explicitly listed in `sre.config.yaml` `overrides.skip_phases`)
- Estimated complexity: **high** — 28 deployable Spring Boot services, zero SRE artifacts exist yet, GenAI service present with no `gen_ai.*` instrumentation, all services need business-span retrofitting on top of an existing auto-instrumentation agent.
- Stack detected: Java 25 (Eclipse Temurin) / Spring Boot 4.0.x, Maven multi-module monorepo, PostgreSQL, Kafka, Eureka service discovery, Spring Cloud Gateway. No frontend. GenAI via LangChain4j + Ollama in `pos-mcp-server`.

## Stack Detection
- Backend: Java 25 / Spring Boot 4.0.x (Maven multi-module reactor, `groupId: com.positivity`), 28 deployable `pos-*` services + `pos-api-gateway`, plus 9 non-deployed shared libraries (`pos-archunit`, `pos-bulk-ingest-lib`, `pos-dependencies`, `pos-document-helper`, `pos-domain-events`, `pos-security-common`, `pos-shared-dtos`, `pos-tax-common`, `pos-coverage-aggregate`).
- Frontend: none detected
- Frontend root: none
- Databases: PostgreSQL 16 / TimescaleDB (Spring Data JPA + Hibernate + Flyway), pgvector (via LangChain4j in `pos-mcp-server`)
- Messaging: Kafka (Spring Kafka, domain events per ADR-0044)

### Instrumentation State (canonical — do not re-detect)

All 28 deployable services share an identical Dockerfile pattern: the **Grafana OpenTelemetry Java Agent** (`grafana-opentelemetry-java.jar`, v2.9.0 — a distribution of the OTel Java auto-instrumentation agent) is attached via `-javaagent` at container entrypoint, with `OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318`, `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf` set as env vars (e.g. [pos-order/Dockerfile](../pos-order/Dockerfile#L1-L21)). No manual OTel SDK initialization (`OpenTelemetrySdk.builder()`, `SdkTracerProvider.builder()`) was found anywhere in real service source — so there is no double-init risk. No business spans or `app.operation.*` attributes were found in any service; the only manual OTel API usage found is `Span.current()` in `pos-mcp-server` ([NltiRequestServiceImpl.java](../pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/NltiRequestServiceImpl.java#L94-L100)) which attaches correlation-id/user-id attributes to the agent-created span — not a full business span.

```yaml
instrumentation_state:
  backend:
    state: api-only-no-spans   # agent present, no SDK init, no app.operation.* business spans yet
    agent: otel-java-agent     # Grafana distribution of the OTel Java auto-instrumentation agent
    agent_version: "v2.9.0"
    applies_to: all 28 pos-* deployable services (uniform Dockerfile pattern)
  frontend:
    state: none
    framework: none
  genai:
    state: none                # no gen_ai.* semconv, no sigil-sdk/openllmetry/openinference found
    providers: [langchain4j, ollama]
    scope: pos-mcp-server only
```

### Detection Flags (Orchestrator reads these for phase decisions)
- brownfield_required: **true** (all 28 services are `api-only-no-spans`, not `greenfield`/`none`)
- frontend_detected: **false**
- genai_detected: **true**
- genai_providers: [langchain4j, ollama]
- otel_destination: **local** (per `sre.config.yaml`: `http://localhost:4318`, `http/protobuf`, UI `http://localhost:3000`). Note: actual service Dockerfiles point `OTEL_EXPORTER_OTLP_ENDPOINT` at `http://otel-collector:4318` (docker-compose network alias) — not a conflict, just a different runtime context (compose network vs. host-local default); confirm intended target with the team before Phase 5.
- blocking_states: [] (no `double-init` or `mixed-conflict` services found — brownfield analysis is required but not CI-blocking)

## Existing SRE Artifacts (inventory)
| Artifact | Path | Status |
|---|---|---|
| Domain map | `docs/domain/domain-map.md` | missing (dir has only `.gitkeep`) |
| Operations catalog | `docs/domain/operations.yaml` | missing |
| Observability plan | `docs/observability/plan.md` | missing (dir has only `.gitkeep`) |
| Backend instrumentation | source (all 28 services) | agent-only; no business spans |
| Frontend instrumentation | n/a | not applicable — no frontend |
| SLI/SLO specs | `packs/slo/examples/*.sli-slo-spec.md` | missing (no `examples/` dir) |
| Alert policies | `packs/slo/alert-policies/` | missing (dir has only `.gitkeep`) |
| Recording rules | `packs/slo/recording-rules/` | missing (dir has only `.gitkeep`) |
| Grafana alerting | `packs/grafana/alerting/burnrate.rules.json` | missing (`packs/grafana/` does not exist at repo root; only exists under `.sre/` as framework reference) |
| Grafana dashboards | `packs/grafana/dashboards/generated/foundation-sdk/` | missing |
| Generated OTel/Grafana catalogs | `docs/generated/*.yaml` | missing (only exist as examples under `.sre/examples/docs/generated/`) |
| Runbooks | `docs/runbooks/` | present but unrelated (`flyway-baseline-reset.md` only); no alert-policy runbooks |
| Agents | `.github/agents/*.agent.md` | present — full agent set already copied to consumer repo root (orchestrator, planner, domain analyst, observability engineer, GenAI assistant/instrumentation-engineer/cost-guardian/evaluator, backend/frontend OTel instrumentation, brownfield analyst, SLI/SLO engineer, Grafana alerting/dashboard generator, incident readiness, progress tracker, quality reviewer, `otel-michelin`) |
| `docs/sre-todo.md` | this file | did not exist before this run — created now |

## TODO List

### Phase 0b - Brownfield Analysis (condition: brownfield_required = true)
- [ ] **[Phase 0b] Assess brownfield instrumentation** → `Brownfield Analyst`
  - Status: pending
  - Condition: brownfield_required = true (met — all 28 services are `api-only-no-spans`)
  - Inputs: `docs/sre-todo.md` (instrumentation state above), source code across all `pos-*` services
  - Outputs: `docs/domain/brownfield-assessment.md`
  - Done criteria:
    - [ ] brownfield-assessment.md exists with all required sections
    - [ ] Every service with non-greenfield state assessed (all 28)
    - [ ] Every existing span classified (conformant/fixable/non-conformant/redundant/must-fix) — includes the `Span.current()` correlation-id pattern in `pos-mcp-server`
    - [ ] Overall recommendation stated per service (expected: keep Grafana OTel Java agent, add business spans via OTel API only — do not add SDK init)
    - [ ] Must-fix items listed for immediate attention

### Phase 1 - Domain Analysis
- [ ] **[Phase 1] Create domain map** → `Repo Domain Analyst`
  - Status: pending
  - Inputs: `docs/sre-todo.md` (stack detection above), source tree (28 `pos-*` services + gateway), `AGENTS.md`, `CLAUDE.md`, `docs/ARCHITECTURE_GUIDE.md`, OpenAPI specs per service
  - Outputs: `docs/domain/domain-map.md`, `docs/domain/operations.yaml`
  - Done criteria:
    - [ ] domain-map.md has bounded contexts and CUJs
    - [ ] operations.yaml parses and passes `validate:operations-yaml`
    - [ ] Every CUJ has at least one operation
    - [ ] All span names follow `{Verb} {BusinessObject}`
    - [ ] No span name contains IDs or high-cardinality tokens

### Phase 2 - Observability Strategy
- [ ] **[Phase 2] Create observability plan** → `Observability Engineer`
  - Status: pending
  - Inputs: domain-map.md, operations.yaml, `.sre/packs/recipes/spans/`, `.sre/packs/recipes/slis/`
  - Outputs: `docs/observability/plan.md`
  - Done criteria:
    - [ ] Signal strategy per tier defined
    - [ ] All Tier 1 operations have instrumentation guidance
    - [ ] Span recipe referenced for each operation type
    - [ ] SLI recipe referenced for each SLI type
    - [ ] Attributes validated against taxonomy
    - [ ] Sampling strategy defined

### Phase 2g - GenAI Observability (condition: genai_detected = true — MET)
- [ ] **[Phase 2g] Populate GenAI observability contract** → `GenAI Observability Assistant`
  - Status: pending
  - Condition: genai_detected = true (LangChain4j + Ollama in `pos-mcp-server`)
  - Inputs: operations.yaml, `pos-mcp-server` source (`AiServices`/tool definitions), `.sre/packs/recipes/genai/genai-implementation-guide.md`
  - Outputs: `genai` block in operations.yaml, `.sre/tools/genai/contracts/` validated
  - Done criteria:
    - [ ] genai block in operations.yaml populated with providers (langchain4j/ollama), models, agents/tools from `pos-mcp-server`
    - [ ] genai block passes schema validation against `.sre/tools/genai/contracts/operations-genai-schema.json`
    - [ ] Each GenAI provider has model pricing defined (note: Ollama is self-hosted — cost model may be zero/infra-cost based; confirm with SLO/SRE Engineer)
    - [ ] Content capture policy set (enabled/disabled with redactor profile) — flag prompt/response PII risk given NLTI request/response flow

### Phase 3a - Backend Instrumentation
- [ ] **[Phase 3a] Instrument backend** → `OTel Instrumentation - Backend`
  - Status: pending
  - Inputs: operations.yaml, plan.md, source code (all 28 services)
  - Outputs: modified source files with business spans
  - Done criteria:
    - [ ] Instrumentation state already determined above (`api-only-no-spans`, Grafana OTel Java agent v2.9.0) — recorded in operations.yaml, not re-detected
    - [ ] Every Tier 1 operation has a manual business span using OTel API only (agent present — do NOT add SDK init, per `.sre/packs/otel/java/README.md`)
    - [ ] Span names match operations.yaml exactly
    - [ ] Required attributes set per taxonomy (`app.operation.outcome`, not `app.operation.result`)
    - [ ] Errors recorded with span status + exception event
    - [ ] Trace context propagated to downstream calls (REST via gateway / load-balanced RestClient, and Kafka domain events)
    - [ ] Log correlation fields present with `isSpanContextValid` guard
    - [ ] `validate:otel` and `validate:span-contract` pass

### Phase 3b - Frontend Instrumentation
- [ ] **[Phase 3b] Instrument frontend** → `OTel Instrumentation - Frontend`
  - Status: **skip** (no frontend detected; also explicitly in `sre.config.yaml` `overrides.skip_phases`)
  - Condition: frontend_detected = false
  - No further action required.

### Phase 3g - GenAI Instrumentation (condition: genai_detected = true AND Phase 2g complete)
- [ ] **[Phase 3g] Instrument GenAI operations** → `GenAI Instrumentation Engineer`
  - Status: pending (blocked on Phase 2g)
  - Condition: genai_detected = true AND Phase 2g complete
  - Inputs: operations.yaml (with genai block), `.sre/packs/recipes/genai/genai-implementation-guide.md`, `pos-mcp-server` source
  - Outputs: modified `pos-mcp-server` source with GenAI spans, token metrics, tool/agent spans
  - Done criteria:
    - [ ] Every operation in `genai.operations` has a conformant span
    - [ ] Every agent in `genai.agents` has an `invoke_agent` parent span
    - [ ] Every tool has an `execute_tool` child span
    - [ ] Token usage metric emitted with correct attribute set (`gen_ai.client.token.usage`)
    - [ ] PII redactor wired (content capture policy from 2g) — prompts/NLTI requests may contain user data
    - [ ] `validate-genai-semconv.ts` exits 0
    - [ ] `detect-genai-instrumentation.ts` returns single source (not `mixed-conflict`) — currently `none`, target is `manual-otel`

### Phase 4 - SLI/SLO Specs
- [ ] **[Phase 4] Define SLIs and SLOs** → `SLI/SLO Engineer`
  - Status: pending
  - Inputs: operations.yaml, plan.md
  - Outputs: `packs/slo/examples/{service}.sli-slo-spec.md`, `packs/slo/alert-policies/`, `packs/slo/recording-rules/`
  - Done criteria:
    - [ ] SLI/SLO spec exists for each service with Tier 1 ops
    - [ ] Each SLI has good/bad event criteria
    - [ ] Each SLO has numeric target and window
    - [ ] Burn-rate parameters defined (fast + slow)
    - [ ] Provider-neutral alert policies exist for Tier 1 SLOs
    - [ ] Error budget documented
    - [ ] operations.yaml updated with SLI/SLO references

### Phase 5a - Grafana Alerting
- [ ] **[Phase 5a] Generate Grafana alerting rules** → `Grafana Alerting`
  - Status: pending
  - Inputs: operations.yaml, plan.md, `packs/slo/recording-rules/`, `packs/slo/alert-policies/`, generated catalogs (`docs/generated/otel-semantic-validation-catalog.yaml`, `docs/generated/grafana-recording-rule-catalog.yaml`, `docs/generated/grafana-runtime-context-catalog.yaml` — **not yet generated in this repo, must be produced first**), `.sre/docs/standards/cards/alert-card.md`
  - Outputs: `packs/grafana/alerting/burnrate.rules.json`
  - Done criteria:
    - [ ] Generated OTel/Grafana catalogs are present and fresh under `docs/generated/`
    - [ ] Grafana rules exist for Tier 1 burn-rate alerts
    - [ ] Rule expressions reference `sli:*:burn_rate_*` recording rules, not raw PromQL
    - [ ] Required labels exist: severity, team, service, slo
    - [ ] Required annotations exist: summary, runbook, dashboard
    - [ ] `validate:grafana-alerting` passes
    - [ ] Confirm final OTLP/Grafana destination (`local` vs `grafana-michelin`) with team before wiring dashboards/alerts — resolve the endpoint discrepancy noted above (localhost:4318 in config vs otel-collector:4318 in Dockerfiles)

### Phase 5b - Grafana Dashboards
- [ ] **[Phase 5b] Generate Grafana Foundation SDK dashboards** → `Grafana Dashboard Generator`
  - Status: pending
  - Inputs: operations.yaml, plan.md, `packs/slo/recording-rules/`, `packs/grafana/alerting/burnrate.rules.json`, generated catalogs under `docs/generated/` (panel catalog, dashboard-intent-model, telemetry-resolution-model, foundations-build-plan — **not yet generated**), `.sre/packs/grafana/specs/foundation-sdk/typescript/`, `.sre/docs/standards/cards/dashboard-card.md`
  - Outputs: `packs/grafana/dashboards/generated/foundation-sdk/`
  - Done criteria:
    - [ ] Generated OTel catalogs are present and fresh
    - [ ] Foundation SDK dashboard suite exists: service overview, SLO detail, domain overview, incident triage
    - [ ] Panels are selected from semantic convention dashboardCompatibility metadata
    - [ ] Burn-rate panels reference SLI recording rules
    - [ ] Required variables and tags are present
    - [ ] Metrics, logs, and traces use bounded service selectors
    - [ ] `validate:dashboards` passes

### Phase 6 - Incident Readiness
- [ ] **[Phase 6] Create runbooks** → `Incident Readiness`
  - Status: pending
  - Inputs: SLI/SLO specs, provider-neutral alert policies, Grafana alert rules, operations.yaml
  - Outputs: `docs/runbooks/`
  - Done criteria:
    - [ ] Every alert policy has a runbook file
    - [ ] Every runbook has all five required sections, in order: Business Impact, Customer Impact, Blast Radius, Telemetry Investigation, Ownership & Escalation
    - [ ] Alert policy runbook references point to valid files
    - [ ] Telemetry Investigation has 3-6 entries with question/datasource/query/why fields using real, grounded metric and attribute names
    - [ ] Zero remediation content in any runbook — `npm run validate:runbook-lint` passes
    - [ ] Escalation uses team names not individuals

### Phase 7 - Quality Review
- [ ] **[Phase 7] Final quality review** → `SRE Quality Reviewer`
  - Status: pending
  - Inputs: diff of all outputs from phases 1–6
  - Outputs: structured review with must-fix/should-fix/optional
  - Done criteria:
    - [ ] All must-fix issues resolved
    - [ ] All validators pass
    - [ ] Artifacts consistent with operations.yaml

## Prioritization Notes
1. Dependency chain is a hard requirement: 0b → 1 → 2 → (2g parallel-ready once operations.yaml exists) → 3a/3g → 4 → 5a → 5b → 6 → 7.
2. Business impact: focus Phase 1 domain analysis on transaction-critical flows first (order, price, inventory, accounting, customer) before peripheral services (vehicle-reference-carapi/nhtsa, documents, image).
3. Quick wins: Phase 0b and Phase 1 can proceed immediately — no blockers. Phase 5a/5b will need catalog generation (`.sre/tools`) run first, which is a quick automated step once operations.yaml and recording rules exist.

## Blockers / Open Items
- `service.team` in `sre.config.yaml` is `TBD` — needed for alert/runbook ownership metadata (Phase 4/6 done criteria require team/owner labels). Flag for the team to fill in before Phase 4.
- OTLP endpoint discrepancy: `sre.config.yaml` declares `otel_destination.target: local` (`http://localhost:4318`) but all service Dockerfiles hardcode `http://otel-collector:4318` (docker-compose network). Not a functional conflict, but confirm which is authoritative before Grafana wiring (Phase 5a/5b) and before `otel-michelin` migration is considered.
- No `docs/generated/*` catalogs exist yet in this repo (only as examples under `.sre/examples/docs/generated/`). These must be produced by the `.sre/tools` generators before Phase 5a/5b can run — not a hard blocker since tooling and `node_modules` are present under `.sre/tools/`, just sequencing.
- GenAI cost/pricing model for Ollama (self-hosted, plus a cloud fallback model `gpt-oss:120b` per `docker-compose.yml`) is non-standard — flag for GenAI Cost Guardian once Phase 2g/3g land.
