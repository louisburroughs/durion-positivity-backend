# PRD: Natural Language Task Interface (NLTI) + MCP Tool Registry

**Status:** Draft  
**Version:** 1.0  
**Date:** 2026-03-11  
**Domain:** Positivity (`pos-mcp-server`)  
**Capability:** natural-language  

---

## 1. Executive Summary

### Problem Statement

Durion Positivity POS operators and staff must navigate multiple domain screens (workorders, inventory, accounting, invoicing) to complete common workflows. There is no unified, conversational interface that lets a user express a goal in natural language and have the system safely execute the required steps.

### Proposed Solution

Build a Natural Language Task Interface (NLTI) on top of the existing Durion Positivity backend. NLTI converts free-form user requests into structured intents, generates safe execution plans, and dispatches them through an authorized MCP Tool Registry to the correct domain service adapters — all with full audit trails, confirmation gates for high-risk actions, and production-grade observability.

### Success Criteria

| KPI | Target |
|-----|--------|
| NLTI request-to-plan latency (p95) | ≤ 2 000 ms |
| Intent parse accuracy on benchmark utterance set | ≥ 90 % |
| Authorization bypass incidents | 0 |
| Audit event chain completeness (request → plan → execution) | 100 % |
| MCP tool candidate set size after registry filtering | 3–5 tools per request |
| Adaptive tuning priority convergence window | 7 days rolling |

---

## 2. User Personas

| Persona | Role | Needs |
|---------|------|-------|
| **Service Advisor** | Front-desk POS operator | Quick lookup: customer, vehicle, workorder status, open invoices |
| **Shop Manager** | Manages technicians and work queue | Daily summary, close workorders, reassign jobs |
| **Accountant / Bookkeeper** | Reviews financial records | List unpaid invoices, reprocess payments, explain suspense entries |
| **Inventory Clerk** | Manages parts stock | Low-stock reports, count adjustments |
| **SRE / Operator** | Platform reliability | Metrics, tracing, runbook execution |
| **Admin** | Registry and permission management | Manage tool metadata, roles, workflow gates |

---

## 3. User Stories & Acceptance Criteria

### Story 1 — NLTI Foundation: API Envelope + Session + Correlation (NLTI-001)

**As** an authenticated POS user,  
**I want** a stable NLTI entry point with session tracking and correlation IDs,  
**so that** every request is unambiguously traceable across all downstream systems.

**Acceptance Criteria:**

- `POST /v1/nlt/requests` with a valid `prompt` returns HTTP 200/202 with `correlationId`, `sessionId`, `requestId`, and `status`.
- Missing `prompt` returns HTTP 400 with `{status:"ERROR", code:"VALIDATION_ERROR", correlationId, details[]}`.
- Inbound `X-Correlation-Id` is echoed in the response and propagated to all logs and traces.
- Repeated requests sharing the same `sessionId` reuse existing session metadata (no duplicate session).
- Rate-limit enforced per session and per subject.

**Non-Goals:**  
Conversation memory / history beyond session-scoped metadata is out of scope for this story.

---

### Story 2 — Intent Model + Clarification State Machine (NLTI-002)

**As** an authenticated POS user,  
**I want** my free-form request parsed into a typed intent with slot extraction and a clarification loop when ambiguous,  
**so that** the system understands what I want before taking any action.

**Acceptance Criteria:**

- Clear read-only request → `intentType=QUERY`, `status=READY`, slots with per-slot confidence scores.
- Ambiguous request → `status=NEEDS_CLARIFICATION` with suggested clarifying questions and at least two options.
- User answer to clarification → transition to `READY` (all slots resolved) or remain `PENDING_CLARIFICATION` (still ambiguous).
- Destructive or bulk intents → `riskLevel=HIGH` flagged for downstream confirmation gate.
- `nlt.intent.parse.count` and `nlt.intent.clarification.count` metrics emitted.

**Out of Scope:** Persistent multi-session conversation history.

---

### Story 3 — Authorized Tool Registry API: Descriptor + RBAC Filtering (NLTI-003)

**As** a planning engine,  
**I want** a versioned, authorization-filtered list of available tool/action descriptors,  
**so that** plans never reference actions a user is not permitted to invoke.

**Acceptance Criteria:**

- `GET /v1/nlt/tools?service={domain}` returns only actions the authenticated subject can call.
- If AuthZ service is unavailable, endpoint returns HTTP 503 and logs `correlationId` (fail-closed).
- Unauthorized invocation attempt returns `NOT_AUTHORIZED` with `correlationId` without calling downstream.
- Discovery logged with `correlationId`, `subjectId`, returned action count.
- Metric `nlt.registry.discovery.denial_count` emitted on denied discoveries.

---

### Story 4 — Planning Engine: Intent → PlanV1 (NLTI-004)

**As** an authenticated POS user,  
**I want** my parsed intent converted into a deterministic, ordered execution plan,  
**so that** I can review what will happen before any action is taken.

**Acceptance Criteria:**

- `READY` intent → `PlanV1` with `planId`, `correlationId`, `intentId`, ordered `steps[]`, `preconditions[]`, `riskLevel`, `requiresConfirmation`, `planSummaryText`.
- Each step has `stepId`, `actionId`, `description`, `inputs`, `expectedOutcome`, `idempotencyKey`.
- Determinism: identical `IntentV1` input produces semantically-equivalent `PlanV1`.
- Unavailable or unauthorized tool → structured planning error (`NOT_AUTHORIZED` or `TOOL_UNAVAILABLE`) with `correlationId`.
- Metric `nlt.planning.latency_ms` and `nlt.planning.error_count` emitted.

---

### Story 5 — Execution Orchestrator: Step Runner + Idempotency (NLTI-005)

**As** an authenticated POS user whose plan is approved,  
**I want** the system to execute plan steps safely, one at a time, with retries on transient failures,  
**so that** my task completes reliably even under intermittent errors.

**Acceptance Criteria:**

- Steps execute in declared order; each completed step is recorded before proceeding.
- Re-submitting the same `executionId` or step `idempotencyKey` does not produce duplicate mutations.
- Transient failure → exponential backoff retries up to configurable max attempts.
- Permanent failure → execution marked `PARTIAL_FAILURE` / `FAILED` with failed step details and partial results in response.
- Metrics `nlt.execution.start`, `nlt.execution.step.completed`, `nlt.execution.step.failed` emitted with `executionId` and `correlationId`.

---

### Story 6 — Preview + Confirmation Gate: Risk-Based Execution Control (NLTI-006)

**As** an authenticated POS user,  
**I want** to preview plans and explicitly confirm high-risk actions before execution,  
**so that** destructive or bulk operations cannot run without my approval.

**Acceptance Criteria:**

- `GET /v1/nlt/plans/{planId}` returns step list, `riskLevel`, `requiresConfirmation`, estimated impact.
- `POST /v1/nlt/plans/{planId}/confirm` records `userId`, timestamp, and a confirmation token tied to the session.
- `HIGH` risk plan blocks execution unless an unexpired confirmation record exists for the same user and session.
- Confirmation attempted by a different user → HTTP 403 and recorded in audit log.
- Expired confirmation token → plan returns to pre-confirmation state.

---

### Story 7 — Audit Trail: Append-Only Request/Plan/Execution Ledger (NLTI-007)

**As** a Positivity auditor,  
**I want** an immutable, queryable ledger of all NLTI events,  
**so that** every automated action can be reviewed, explained, and traced to its originating user request.

**Acceptance Criteria:**

- Every execution produces a complete event chain: `REQUEST` → `INTENT` → `PLAN` → `CONFIRMATION` (if required) → `EXECUTION` steps.
- `GET /v1/nlt/audit?correlationId=&from=&to=&eventType=` returns paginated events in timestamp order.
- Sensitive data is not stored in plaintext; oversized payloads are stored by reference (blob store) with only metadata in the ledger.
- `nlt.audit.write_failures` metric emitted; write failure above threshold blocks destructive execution.
- Audit writes are durable and idempotent from the writer's perspective.

---

### Story 8 — Domain Tool Adapters v1: WorkExec + Accounting (NLTI-008)

**As** a planning/execution engine,  
**I want** working adapters for the initial domain action set,  
**so that** NLTI can execute real tasks for the most common POS workflows.

**Acceptance Criteria:**

- Each adapter implements: `validate(inputs)`, `transform(inputs)`, `call()`, `normalizeOutput()` → returns `ActionResultV1{status, summaryText, details}`.
- **WorkExec actions:** `listCompletedWorkOrders`, `closeWorkOrder`, `dailySummary`.
- **Accounting actions:** `listUnpaidInvoices`, `reprocessPayment` (requires high privilege + confirmation).
- Invalid or missing inputs → structured `ERROR` response, downstream not called.
- Unauthorized user → `NOT_AUTHORIZED` response, downstream not called.
- Each adapter has unit + contract tests validating validation, error, and idempotency behavior.

---

### Story 9 — Observability & Operations: Metrics, Tracing, Alerts, Runbooks (NLTI-009)

**As** an SRE,  
**I want** dashboards, alerts, and runbooks for NLTI,  
**so that** failures are detected within minutes and resolved with clear, documented remediation steps.

**Acceptance Criteria:**

- Required metrics: `nlt.request.count`, `nlt.request.latency_ms`, `nlt.planning.latency_ms`, `nlt.execution.latency_ms`, `nlt.error.count`, `nlt.audit.write_failures`.
- All traces carry: `correlationId`, `requestId`, `userId`, `actionId`, `planId`, `executionId` as span attributes.
- Dashboards show p50/p95/p99 latency, error rates, top failing `actionId`s, audit write failures.
- Alerts fire for: sustained high error rate, latency regression, audit write spike, confirmation gate failures.
- Runbooks written for: AuthZ outage, downstream tool timeouts, audit storage failure, planning failures, confirmation gate mismatch.
- Dashboards and alerts verified in staging; runbooks validated by SRE tabletop exercise.

---

### Story 10 — Guidance Mode: "How do I…" Answers + Convert-to-Plan (NLTI-010)

**As** a POS user unfamiliar with a workflow,  
**I want** the system to answer "how do I…" questions with numbered steps,  
**so that** I can either follow them manually or convert them to an executable plan.

**Acceptance Criteria:**

- How-to query detected → `GuidanceResponseV1{guidanceTitle, steps[], notes[], supportedForExecution, estimatedRisk}`.
- If `supportedForExecution=true` and user requests convert-to-plan → deterministic `PlanV1` suitable for preview and confirmation.
- User lacks permission for a step → that step is omitted with actionable guidance to request access.
- Guidance is grounded in approved internal documentation; no hallucinated instructions.

---

## 4. MCP Tool Registry — pos-mcp-server Enhancement

### Overview

The MCP server currently proxies all downstream OpenAPI services. The Tool Registry narrows the exposed tool set using role/workflow/intent gating + embedding-based semantic ranking so the LLM context window is never flooded with irrelevant tools.

### Functional Requirements

#### FR-MCP-1: Data Model and Persistence

The registry stores tool metadata in PostgreSQL using pgvector:

```
mcp_tool             — core tool descriptor (name, description, domain, priority, embedding, cost_level, avg_latency_ms, handler_bean)
mcp_role             — mirrors security-service canonical role codes (no local-only roles)
mcp_tool_role        — M:M tool ↔ role allowlist
mcp_workflow_state   — named workflow states (IDLE, CREATING_PO, RECEIVING_ASN, INVENTORY_RECON, …)
mcp_tool_workflow    — M:M tool ↔ workflow state allowlist
mcp_intent           — intent codes mapped to tool candidates
mcp_intent_tool      — M:M intent ↔ tool
mcp_tool_invocation_log — audit log per selection/invocation with outcomes
```

- All IDs are UUIDv7. All entities carry `createdAt`/`updatedAt`.
- `mcp_role` entries are synchronized from security-service at startup; unknown roles fail closed.

#### FR-MCP-2: Configurable Embedding Provider

- Default provider: **OpenAI** (model and timeout configurable via application properties).
- Provider strategy pattern: `EmbeddingService` interface enables alternative provider injection (Azure, disabled).
- Safe degraded behavior when the embedding provider is unavailable: fall back to role/workflow/intent filter only.

```properties
pos.mcp.embedding.provider=openai       # openai | azure | disabled
pos.mcp.embedding.openai.model=text-embedding-3-small
pos.mcp.embedding.openai.timeout-ms=3000
```

#### FR-MCP-3: Registry Resolution Core

Candidate resolution algorithm (executed per request):

1. **Pre-filter** by `role` + `workflowState` + `intent` → eligible tool set.
2. **Embed** user query via `EmbeddingService.embed(userInput)`.
3. **Vector top-K** retrieval from pre-filtered set (`pgvector` cosine distance).
4. **Deterministic scoring:**

```
score = (semantic_rank_inverse * 0.5) + (priority * 0.3) - (normalized_latency * 0.15) - (cost_weight * 0.05)
```

1. Return **top 3–5** candidates in stable, repeatable order.

Output: `List<ToolMetadata>` injected into MCP context for current request.

#### FR-MCP-4: Session-Store Workflow State Integration

- Workflow state persisted/retrieved from session store abstraction (not HTTP session).
- Safe default: `IDLE` when state missing or expired.
- Observability for stale state transitions; concurrency and expiry safeguards required.

#### FR-MCP-5: Audit Logging and Adaptive Priority Tuning

**Invocation log** captured immediately after each tool call:

| Column | Description |
|--------|-------------|
| `tool_id` | FK to `mcp_tool` |
| `user_id`, `session_id` | Actor context |
| `intent`, `workflow_state` | Request context |
| `semantic_rank`, `final_score` | Selection metadata |
| `selected`, `success`, `fallback_invoked` | Outcome booleans |
| `execution_time_ms`, `error_type` | Performance data |

**Adaptive priority tuning** (daily scheduled job, 7-day rolling window):

```
performance_score = (success_rate * 0.6) + ((1 − normalized_latency) * 0.3) − (fallback_rate * 0.2)
normalized_latency = min(avg_latency_ms / 2000, 1.0)
new_priority = clamp(old_priority * 0.7 + performance_score * 0.3, 0.1, 1.0)
```

- **Enabled by default.** Runtime toggle: `pos.mcp.adaptive-tuning.enabled=false`.
- Minimum sample threshold before tuning applies: 10 invocations per tool.
- Outlier latency (> 3× p99) excluded from rolling window.

#### FR-MCP-6: Admin / Write APIs

All write endpoints require explicit `@PreAuthorize` permission guards:

| Endpoint | Permission Required |
|----------|---------------------|
| `POST /v1/mcp/tools` | `mcp:tool:write` |
| `PUT /v1/mcp/tools/{id}` | `mcp:tool:write` |
| `DELETE /v1/mcp/tools/{id}` | `mcp:tool:admin` |
| `POST /v1/mcp/tools/{id}/roles` | `mcp:tool:write` |
| `POST /v1/mcp/tools/{id}/workflows` | `mcp:tool:write` |
| `POST /v1/mcp/tools/{id}/intents` | `mcp:tool:write` |

- All state-changing endpoints carry `@EmitEvent` and register event types at startup.
- `mcp:tool:read` permission for list/get operations.

---

## 5. Technical Specifications

### Architecture Overview

```
User (UI/API)
    │
    ▼
API Gateway (auth, rate-limit, header forwarding)
    │
    ▼
pos-mcp-server (ENHANCED EXISTING MODULE)
    ├── NltController          POST /v1/nlt/requests
    ├── IntentParserService    NLP classification + slot extraction
    ├── PlanningService        IntentV1 → PlanV1
    ├── ConfirmationService    Risk gate + token management
    ├── ExecutionOrchestrator  Sequential step runner + idempotency
    ├── AuditLedgerService     Append-only event ledger
    ├── ToolRegistryService    Role/workflow/intent/embedding resolution
    ├── EmbeddingService       Provider-configurable (OpenAI default)
    ├── ToolAuditService       Invocation log persistence
    ├── ToolPriorityTuningService  Daily adaptive reweighting
    └── AdminController        RBAC-guarded write APIs
    │
    ▼
Domain Services (pos-workorder, pos-accounting, pos-inventory, pos-customer, …)
```

### Data Flow: Request → Execution

```
1. POST /v1/nlt/requests          { prompt, sessionId?, clientContext? }
2. IntentParserService            → IntentV1 (type, slots, confidence, riskLevel)
   └── If NEEDS_CLARIFICATION     ← return clarification response to user
3. ToolRegistryService            Resolve tools (auth-filtered + embedding-ranked)
4. PlanningService                IntentV1 + tools → PlanV1 (steps, preconditions)
5. ConfirmationService            If riskLevel=HIGH → require confirmation token
6. ExecutionOrchestrator          Execute steps via domain tool adapters
7. AuditLedgerService             Append event chain to ledger
```

### Module Location

- Enhanced existing module: `pos-mcp-server/` (NLTI + MCP registry features implemented here)

### Package Conventions

All code follows the mandatory internal package structure from `AGENTS.md`:

```text
com.positivity.mcp/
├── PositivityMcpServerApplication.java   ← @SpringBootApplication (root)
├── service/                              ← PUBLIC API (service interfaces only)
│   ├── NltiRequestService.java
│   ├── IntentParserService.java
│   ├── PlanningService.java
│   ├── ExecutionOrchestratorService.java
│   ├── AuditLedgerService.java
│   └── ToolRegistryService.java
└── internal/
    ├── controller/                       ← nlt + mcp endpoints
    ├── repository/                       ← nlti + mcp tables
    ├── entity/
    ├── dto/
    ├── config/
    ├── domain/
    ├── enums/
    ├── adapter/                          ← domain adapters
    └── client/                           ← external clients (authz, embeddings)
```

### API Contracts

#### `RequestResponseV1` (NLTI response envelope)

```json
{
  "requestId":     "uuid",
  "correlationId": "uuid",
  "sessionId":     "uuid",
  "status":        "ACCEPTED | COMPLETE | ERROR",
  "result":        { ... },
  "meta": {
    "durationMs": 142,
    "validationIssues": []
  }
}
```

#### `IntentV1`

```json
{
  "intentId":    "uuid",
  "intentType":  "QUERY | ACTION | UNKNOWN",
  "status":      "READY | NEEDS_CLARIFICATION | PENDING_CLARIFICATION",
  "riskLevel":   "LOW | MEDIUM | HIGH",
  "slots":       [{ "name": "workorderId", "value": "WO-100", "confidence": "HIGH" }],
  "clarificationQuestions": []
}
```

#### `PlanV1`

```json
{
  "planId":              "uuid",
  "correlationId":       "uuid",
  "intentId":            "uuid",
  "riskLevel":           "LOW | MEDIUM | HIGH",
  "requiresConfirmation": true,
  "planSummaryText":     "Close workorder WO-100 and send invoice to customer.",
  "preconditions":       [{ "description": "WO-100 exists and is in PENDING status", "met": true }],
  "steps": [
    {
      "stepId":          "uuid",
      "actionId":        "WORKORDER_CLOSE",
      "description":     "Close workorder WO-100",
      "inputs":          { "workorderId": "WO-100" },
      "expectedOutcome": "Workorder status set to CLOSED",
      "idempotencyKey":  "sha256-of-inputs"
    }
  ]
}
```

#### `ActionResultV1` (adapter output)

```json
{
  "status":      "OK | ERROR | NOT_AUTHORIZED",
  "summaryText": "Closed workorder WO-100 successfully.",
  "details":     { ... }
}
```

#### `ToolDescriptorV1` (registry entry)

```json
{
  "actionId":           "WORKORDER_CLOSE",
  "description":        "Close a workorder and trigger invoice generation",
  "inputSchema":        { ... },
  "outputSchema":       { ... },
  "riskLevel":          "HIGH",
  "requiredPermissions": ["workorder:write"],
  "version":            "1"
}
```

### Integration Points

| System | Integration | Notes |
|--------|-------------|-------|
| API Gateway | Inbound auth + header forwarding | `X-Correlation-Id`, `X-User-Subject` |
| pos-security-service | AuthZ checks | Fail-closed on unavailability |
| pos-workorder | Tool adapter | via existing service interface |
| pos-accounting | Tool adapter | via existing service interface |
| pos-inventory | Tool adapter | via existing service interface (future) |
| pos-customer | Tool adapter | via existing service interface (future) |
| PostgreSQL + pgvector | Embeddings, registry, audit | Extension must be enabled |
| OpenAI Embeddings API | Semantic embedding | Configurable provider; graceful fallback |
| pos-events | @EmitEvent audit logging | All state-changing endpoints |
| Prometheus / OTel | Metrics + traces | Micrometer; spans with required attributes |

### Security Requirements

- **Authentication:** All endpoints require a valid JWT issued by the security service.
- **Authorization:** Planning and execution check permissions at both discovery time and invocation time (defense in depth).
- **Prompt safety:** Raw prompts never logged in plaintext; store SHA-256 hash or redacted form per audit policy.
- **Confirmation token binding:** Token is user- and session-scoped; cross-user confirmation rejected with HTTP 403.
- **Fail-closed:** Registry, AuthZ, and session-store failures return 503 rather than permitting access.
- **Rate limiting:** Per-session + per-subject limits on NLTI request endpoint.
- **mcp_role integrity:** `mcp_role` table mirrors security-service only; roles not present in security-service cannot access tools.
- **OWASP:** All endpoints follow injection, auth, and access-control requirements.

### Privacy Requirements

- Prompt text: hash or redact before persistence; never store in telemetry attributes.
- Audit ledger: oversized payloads stored in a secure blob store by reference; only metadata in the database ledger.
- PII in slot values: apply field-level redaction rules before audit writes.

---

## 6. Risks & Phased Roadmap

### Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| LLM/embedding provider outage | Medium | High | Fallback to rule-based intent classification + role/workflow filter |
| AuthZ service unavailability | Low | Critical | Fail-closed (HTTP 503); circuit breaker on AuthZ client |
| Audit write failure under load | Medium | High | Alert threshold; block destructive execution when audit is down |
| Confirmation token bypass | Low | Critical | Session + user scope binding; short expiry; audit all 403s |
| pgvector extension unavailable | Low | Medium | Non-vector fallback path in resolver |
| Intent misclassification causing wrong tool | Medium | Medium | Confidence thresholds; human-in-the-loop clarification gate |
| Adaptive tuning diverging tool priorities | Low | Low | Sample floor (≥ 10), clamp range, smoothing factor, toggle to disable |

### Phase 1 — NLTI Foundation (Stories 1, 2, 7, 9)

Deliverables:

- `pos-mcp-server` enhanced with NLTI API endpoint, session management, and correlation propagation.
- Intent parser with classification and slot extraction.
- Audit ledger (append-only) and observability baseline.

Done when: NLTI accepts requests, parses intents, writes audit events, and all metrics/traces are flowing.

### Phase 2 — Tool Registry + Planning (Stories 3, 4, and MCP-FR-1 through FR-4)

Deliverables:

- MCP Tool Registry schema, entities, repositories, embedding service.
- Tool resolution core (role/workflow/intent filter + embedding top-K + deterministic scorer).
- NLTI Authorized Tool Registry API.
- NLTI Planning Engine producing `PlanV1`.

Done when: A `READY` intent can resolve an authorized, embedding-ranked tool set and produce a `PlanV1` with steps and preconditions.

### Phase 3 — Safe Execution (Stories 5, 6, 8, and MCP-FR-5)

Deliverables:

- Confirmation gate for HIGH-risk plans.
- Execution orchestrator with idempotency, retries, partial failure handling.
- WorkExec + Accounting domain tool adapters v1.
- Audit invocation log + adaptive priority tuning in MCP server.

Done when: End-to-end flow from user prompt to workorder close or invoice list is demonstrable in a staging environment with full audit chain.

### Phase 4 — Guidance Mode + Admin APIs (Stories 10, MCP-FR-6)

Deliverables:

- NLTI Guidance Mode with convert-to-plan.
- MCP admin/write APIs with RBAC enforcement and `@EmitEvent` instrumentation.
- Runbooks signed off by SRE.

Done when: "How do I close a workorder?" returns a numbered guidance response that can be converted to a confirmable plan; admin APIs pass RBAC tests.

---

## 7. Agent Task Breakdown

### Agent: Lead Coder

Coordinate decomposition of each phase into Backend/API/Domain/Test specialist subtasks. Gate entry to Phase 2 on Phase 1 acceptance test passage.

### Agent: API Surface Coder

Responsible for all externally-visible DTOs, controller methods, and OpenAPI annotations:

- `NltController` (`POST /v1/nlt/requests`, `GET /v1/nlt/requests/{requestId}`)
- `ToolRegistryController` (`GET /v1/nlt/tools`)
- `PlanController` (`GET /v1/nlt/plans/{planId}`, `POST /v1/nlt/plans/{planId}/confirm`)
- `AuditController` (`GET /v1/nlt/audit`)
- MCP `AdminController` (all write endpoints)
- All `*V1` DTO records and validation annotations
- `@EmitEvent` and event type registry for all state-changing endpoints
- `permissions.yaml` entries for NLTI and MCP scopes

### Agent: Domain Data Coder

Responsible for service implementations, entities, repositories, and domain logic:

- `pos-mcp-server` entities: `NltiRequest`, `Intent`, `Plan`, `PlanStep`, `Confirmation`, `AuditEvent`, `Session`
- `pos-mcp-server` entities: `McpTool`, `McpRole`, `McpToolRole`, `McpWorkflowState`, `McpToolWorkflow`, `McpIntent`, `McpIntentTool`, `McpToolInvocationLog`
- Service implementations: `IntentParserServiceImpl`, `PlanningServiceImpl`, `ExecutionOrchestratorServiceImpl`, `AuditLedgerServiceImpl`
- `ToolRegistryServiceImpl` (resolution core: pre-filter → embed → top-K → score → rank)
- `ToolAuditServiceImpl` and `ToolPriorityTuningService` (adaptive, daily scheduled)
- pgvector repository queries and deterministic fallback queries
- Session store integration for workflow state

### Agent: Client Coder

Responsible for outbound integration in `pos-mcp-server`:

- `AuthZClient` → calls `pos-security-service` for permission checks (fail-closed wrapper)
- Domain tool adapters: `WorkorderToolAdapter`, `AccountingToolAdapter` (WorkExec + Accounting actions listed in Story 8)
- `EmbeddingServiceImpl` (OpenAI provider + provider strategy interface)

### Agent: Backend Testing Agent

Responsible for all test coverage:

- ArchUnit tests for `pos-mcp-server` internal package encapsulation
- Unit tests: intent parser accuracy (benchmark utterances), slot extraction, planner determinism, scorer edge cases
- Contract tests: each tool adapter (validation, error, idempotency)
- Integration tests: clarification flow (ASK → REPLY → READY), confirmation gate, cross-user rejection, expired token
- Integration tests: registry RBAC filtering, role sync fail-closed, embedding fallback
- Integration tests: end-to-end NLTI request → audit event chain completeness
- Adaptive tuning unit tests: sample floor enforcement, clamp, toggle to disable
- Admin API RBAC tests: success + forbidden scenarios

### Agent: Documentation Agent

- Update `pos-mcp-server/README.md` with NLTI module purpose, API summary, registry architecture, config reference, seeding runbook, and local run instructions.
- Document fallback behavior and incident troubleshooting paths.

### Agent: SRE / Observability

- Wire all required metrics into Micrometer/Prometheus configuration.
- Define all trace span attributes in `application-observability.yml`.
- Create dashboard configuration for NLTI KPIs.
- Author alert rules and runbooks (per Story 9 requirements).
- Validate dashboards and alerts in staging; conduct tabletop exercise.

---

## 8. Non-Goals

- Conversation memory spanning multiple independent user sessions.
- LLM fine-tuning or model hosting.
- Frontend / UI implementation (backend contracts only).
- Expanding tool adapters beyond WorkExec and Accounting in Phase 3 (Inventory, CRM deferred to Phase 4+).
- Multi-armed bandit experimentation for per-role adaptive tuning (deferred).

---

## 9. Open Questions

| # | Question | Owner | Target Resolution |
|---|----------|-------|-------------------|
| 1 | Which blob store should back oversized audit payloads? (S3-compatible?) | Architecture | Phase 1 kick-off |
| 2 | Session store technology — Redis or DB-backed? | Architecture | Phase 1 kick-off |
| 3 | LLM model selection for intent parsing — hosted or self-managed? | Platform Engineering | Phase 1 kick-off |
| 4 | Package and namespace strategy for NLTI components within `pos-mcp-server` | Lead Coder | Phase 1 start |
| 5 | Minimum viable clarification UX — does convert-to-plan require frontend support in Phase 3? | Product / Frontend | Phase 2 |

---

## 10. Reference Files

| File | Purpose |
|------|---------|
| [pos-mcp-server/docs/tool-registry-plan.md](../pos-mcp-server/docs/tool-registry-plan.md) | Execution checklist and decision log for MCP registry |
| [pos-mcp-server/docs/tool-registry-implementation.md](../pos-mcp-server/docs/tool-registry-implementation.md) | Reference implementation: entities, repositories, scorer |
| [pos-mcp-server/docs/tool-audit-logging.md](../pos-mcp-server/docs/tool-audit-logging.md) | Audit schema, aggregated performance view, priority formula |
| [pos-mcp-server/docs/domain-facade-tools.md](../pos-mcp-server/docs/domain-facade-tools.md) | Facade tool list, intent routing, role-based registration design |
| [durion/scripts/nlti/durion-positivity-backend/NLTI-001-backend.md](../../durion/scripts/nlti/durion-positivity-backend/NLTI-001-backend.md) | NLTI Foundation story |
| [durion/scripts/nlti/durion-positivity-backend/NLTI-002-backend.md](../../durion/scripts/nlti/durion-positivity-backend/NLTI-002-backend.md) | Intent model story |
| [durion/scripts/nlti/durion-positivity-backend/NLTI-003-backend.md](../../durion/scripts/nlti/durion-positivity-backend/NLTI-003-backend.md) | Tool registry story |
| [durion/scripts/nlti/durion-positivity-backend/NLTI-004-backend.md](../../durion/scripts/nlti/durion-positivity-backend/NLTI-004-backend.md) | Planning engine story |
| [durion/scripts/nlti/durion-positivity-backend/NLTI-005-backend.md](../../durion/scripts/nlti/durion-positivity-backend/NLTI-005-backend.md) | Execution orchestrator story |
| [durion/scripts/nlti/durion-positivity-backend/NLTI-006-backend.md](../../durion/scripts/nlti/durion-positivity-backend/NLTI-006-backend.md) | Confirmation gate story |
| [durion/scripts/nlti/durion-positivity-backend/NLTI-007-backend.md](../../durion/scripts/nlti/durion-positivity-backend/NLTI-007-backend.md) | Audit trail story |
| [durion/scripts/nlti/durion-positivity-backend/NLTI-008-backend.md](../../durion/scripts/nlti/durion-positivity-backend/NLTI-008-backend.md) | Domain tool adapters story |
| [durion/scripts/nlti/durion-positivity-backend/NLTI-009-backend.md](../../durion/scripts/nlti/durion-positivity-backend/NLTI-009-backend.md) | Observability story |
| [durion/scripts/nlti/durion-positivity-backend/NLTI-010-backend.md](../../durion/scripts/nlti/durion-positivity-backend/NLTI-010-backend.md) | Guidance mode story |
| [durion-positivity-backend/AGENTS.md](../AGENTS.md) | Backend conventions (packages, events, null safety) |
