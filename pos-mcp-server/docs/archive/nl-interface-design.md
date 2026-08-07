# Natural-Language Interface Design — pos-mcp-server

> **Status:** DRAFT / design-only (no code changes implied by this document)
> **Scope:** End-to-end design for the conversational NL interface exposed by `pos-mcp-server`.
> **Audience for the interface:** Internal staff (service advisors, technicians, shop/location managers, accounting) **and** platform admins / ops. **Not** end customers.
> **Write policy:** Read + **gated** writes (preview → explicit confirmation → execute).
> **Model strategy:** Self-hosted Ollama, **tiered** (small model for routing/simple, larger model for complex reasoning + writes).
> **Companion specs:** `docs/nlq-to-api-analysis.md`, `docs/nlq-to-api-second-review.md`, `docs/tool-usage-enhancement-spec.md` (referenced by issue #639).

This is a comprehensive, phased plan. It is intended to be executed **step by step**; each phase has a goal, the concrete changes, the artifacts it touches, an exit criterion, a rollback strategy, and the GitHub issues it satisfies. Phases are ordered so each builds on a verified foundation.

---

## 0. Why this document exists

`pos-mcp-server` is already a mature LangChain4j + Ollama MCP server: permission-gated tool selection over pgvector, a Tier-2 RAG retrieval chain, adaptive tool-priority tuning, NLTI session/request/intent/audit modeling, and synchronous + streaming chat. It is **not** greenfield.

Several capabilities are **modeled but not wired**, and the conversational experience is not yet role-aware in the way the PRDs intend. This plan closes those gaps in an order that lets us _measure_ improvement at every step rather than guess.

---

## 0.1 Non-goals (explicit boundaries)

To prevent scope creep, this interface explicitly does **not**:

- Serve an **end-customer-facing** chatbot. Internal staff + admins only.
- Permit **ungated mutations**. Every write goes through preview → confirmation → execute.
- Make **model-only authorization decisions**. Access is decided by permission codes + workflow state, never by the LLM.
- Allow **model-generated SQL** or any direct database access by the model.
- Perform **cross-tenant / cross-scope retrieval**. Retrieval is permission-scoped.
- Take **autonomous background actions** without explicit user confirmation.
- **Replace existing business-rule validation.** Confirmation authorizes an API call; the service layer still enforces all domain rules.

---

## 1. Current-state assessment

### 1.1 What is solid (keep)

- **Permission-gated tool selection.** `ToolRegistryService.resolveCandidateTools()` runs a pgvector ANN query joining `mcp_tool` / `mcp_tool_permission` / `mcp_workflow_state`, gating on the caller's `permissionCodes` **inside** the query, fail-closed (a tool with zero permission rows is never selected). Top-K capped at `mcp.agent.candidate-tool-limit` (8); Exa web search always included.
- **Tier-2 RAG retrieval.** Baseline retriever (k=10, minScore 0.6) + query-expanded retriever (k=20, minScore 0.55) + hybrid merge/dedup + lexical re-rank → top-5. Role-aware metadata filter (`RoleAwareMetadataFilter`, `ScopedContentRetrieverFactory`). Persisted chat memory (`SemanticChatMemoryStore`, `SessionSummary`).
- **16 domain system prompts.** Seeded by `SystemPromptSeedRunner` — concrete, anti-hallucination, status-precise. **These are good. Keep them as the DOMAIN layer (§2.3).**
- **NLTI domain model already supports gated writes.** Real enums in `internal/enums/`:
  - `NltiIntentType` = `QUERY | ACTION | UNKNOWN` — read-vs-write classifier already exists.
  - `NltiRiskLevel` = `LOW | MEDIUM | HIGH` — risk-tiered gating already modeled.
  - `NltiIntentStatus` = `READY | NEEDS_CLARIFICATION | PENDING_CLARIFICATION`.
  - `NltiRequestStatus` = `ACCEPTED | COMPLETE | ERROR`.
  - `NltiAuditEventType` = `REQUEST | INTENT | PLAN | CONFIRMATION | EXECUTION_STEP | EXECUTION_COMPLETE | EXECUTION_FAILED` — full plan → confirm → execute audit chain.
  - `SimpleChatRuleType` (GREETING, THANKS, SOCIAL_QUESTION, CAPABILITY, TASK_REQUEST_PHRASE, BUSINESS_KEYWORD, ACTION_KEYWORD, …) — a rule-based fast-path catalog already exists.
- **Adaptive tuning + audit.** Every selection/execution logged to `mcp_tool_invocation_log`; daily cron (`ToolPriorityTuningService`) recomputes `mcp_tool.priority`.

### 1.2 What is broken / dead (the gaps this plan fixes)

| #   | Gap                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | Evidence       | Issue      |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------- | ---------- |
| G1  | **Role persona prompts are dead code.** `SessionAgentManager:225` sets `promptName = promptNameForRagScope(ragScope)` — persona is chosen by the _RAG scope derived from selected tools_, never by the caller's role. `SystemPromptDefaults` declares `ROLE_*` persona constants + `MCP_ROLE_PRIORITY`, and `McpRoleResolver` resolves a primary role — but `SystemPromptSeedRunner` **never seeds a single `ROLE_*` prompt**, so `resolvePrompt("ROLE_TECHNICIAN")` always misses → falls to `master`. A technician and a controller get the identical persona. | code           | #637       |
| G2  | **Blocking vs streaming chat diverge** in tool-selection behavior; **role preload omits `ROLE_TECHNICIAN` / `ROLE_USER`**; role-agent caches ignore TTL and go stale.                                                                                                                                                                                                                                                                                                                                                                                            | issue text     | #639       |
| G3  | **Workflow state never leaves `IDLE`.** Both session managers always evaluate `WORKFLOW_IDLE`; non-IDLE tool sets (`CREATING_PO`, `PROCESSING_RETURN`) never activate.                                                                                                                                                                                                                                                                                                                                                                                           | README + code  | #778       |
| G4  | **OpenAPI-discovered tools are not agent-callable.** ~500+ operations discovered from the gateway aggregate spec are persisted to `mcp_tool` but no LangChain4j `ToolProvider` serves `source='openapi'` operations, so the assistant can only use the 17 facades.                                                                                                                                                                                                                                                                                               | README + code  | #779, #645 |
| G5  | **Legacy role-gating lingers** alongside permission-gating (`ToolRegistryRoleMapper`, `mcp_role`, `mcp_tool_role`) — competing/dead code.                                                                                                                                                                                                                                                                                                                                                                                                                        | code           | #780       |
| G6  | **No retrieval-quality regression tests.** Adaptive tuning silently mutates priority with no hit@5 / MRR / recall@k gate — improvement is indistinguishable from regression.                                                                                                                                                                                                                                                                                                                                                                                     | README backlog | #783       |
| G7  | **Pure dense retrieval** under-retrieves exact IDs / codes / part numbers.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | README backlog | #784       |
| G8  | **Security-side dependencies** for clean fail-closed gating: `AUTHENTICATED` sentinel not emitted by all services; no role-default-permissions endpoint for cache prebuild.                                                                                                                                                                                                                                                                                                                                                                                      | issue text     | #781, #782 |
| G9  | **Config drift.** `application.yml` default chat model `qwen3.5:cloud` vs README `llama3.1:8b` vs fallback `gpt-oss:120b`; no deliberate documented default.                                                                                                                                                                                                                                                                                                                                                                                                     | code/README    | —          |

---

## 2. Target architecture

### 2.1 Request lifecycle (target)

```
client (JWT → gateway → X-Authorities perm_bits)
        │
        ▼
  CurrentUserContext  ── permissionCodes[], primaryRole (McpRoleResolver)
        │
        ▼
  TIER 0  SimpleChatRule fast-path        (no LLM)
        │  greeting / thanks / capability / social → canned or templated reply
        ▼
  TIER 1  Router / classifier             (SMALL model, temp 0, JSON out)
        │  → NltiIntentType {QUERY|ACTION|UNKNOWN}
        │  → NltiRiskLevel  {LOW|MEDIUM|HIGH}
        │  → rag-scope / domain hint
        │  → complexity {single-lookup | multi-domain}
        ▼
  Tool gating  permissionCodes ∩ mcp_tool_permission ∩ workflowState
        │  (role no longer in the query — #780)
        ▼
  Prompt assembly   BASE + ROLE + DOMAIN + TOOL-USE [+ WRITE-GATE]
        │
        ▼
  TIER 2  Executor   (model chosen by complexity + risk + intent)
        │  single QUERY lookup            → small executor
        │  multi-tool / ACTION / HIGH risk → large executor
        ▼
  QUERY → answer        ACTION → PLAN → CONFIRMATION gate → EXECUTION_*
        │
        ▼
  telemetry event (§5) + audit (mcp_tool_invocation_log + nlti_audit_event) → adaptive tuning (shadow)
```

### 2.2 Tiered model strategy (Ollama, cost-aware)

Route on **complexity and risk**, not just cost. Tool-call accuracy over an 8-tool candidate set is the real quality ceiling for an ERP assistant — small models pick wrong tools and fabricate arguments far more often than 30B+ models.

| Tier       | Job                                                                      | Candidate Ollama model                                                | Rationale                                                           |
| ---------- | ------------------------------------------------------------------------ | --------------------------------------------------------------------- | ------------------------------------------------------------------- |
| T0         | Rule fast-path (no model)                                                | —                                                                     | `SimpleChatRule` catalog already exists                             |
| T1 Router  | Intent + risk + domain classification, strict JSON                       | `qwen3:4b` or `llama3.2:3b`                                           | fast, cheap, deterministic at temp 0 for constrained classification |
| T2 simple  | 1 lookup tool + format                                                   | `qwen3:8b` / `qwen2.5:7b-instruct`                                    | adequate single-tool calling at low cost                            |
| T2 complex | multi-tool, cross-domain synthesis, **all writes**, accounting/tax/admin | `qwen3:32b` / `gpt-oss:120b` (existing fallback) / `qwen3:235b-cloud` | tool-call accuracy + multi-step reasoning                           |

Routing rule of thumb — send to T2-complex when **any** of: ≥2 tool calls expected, `NltiIntentType=ACTION`, `NltiRiskLevel≥MEDIUM`, or domain ∈ {accounting, tax, admin, security}. Everything else → T2-simple.

`mcp.model.fallback` (primary→secondary failover) stays as-is; it is orthogonal to T1/T2 _routing_. Both coexist.

### 2.3 Prompt architecture — layered, role-first

Replace the current tool-derived single prompt with a composed prompt assembled per request:

```
[BASE]       identity; tool-before-answer; never fabricate IDs/quantities/statuses;
             distinguish confirmed fact vs inference; one synthesized answer across domains
   +
[ROLE]       persona for resolved primaryRole  ← THE DEAD LAYER (G1) — wire it
   +
[DOMAIN]     existing 16 domain prompts, keyed by active rag-scope  ← keep, good
   +
[TOOL-USE]   arg-grounding contract; how to ask for a missing arg; never guess identifiers
   +
[WRITE-GATE] injected only when a write-capable tool is in the candidate set (§2.5)
```

Resolution order inverts today's logic: **role-first, domain-second**. The agent cache key already includes `role::toolCacheKey`, so role-layered prompts cache correctly with no key change.

**Boundary — what role controls vs what it does not:**

| Concern                                        | Driven by                                                                                                                 |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| **Persona** (tone, framing, what to emphasize) | primary role                                                                                                              |
| **Tool access**                                | permission codes + workflow state **only** — role never appears in the gating query (#780)                                |
| **RAG document visibility**                    | **permission-aware** metadata filter (primary); role may assist only as a convenience hint, never as the sole gate (§3.3) |

Role personas to seed (drop `ROLE_CUSTOMER` / `ROLE_SELF_SERVICE_CUSTOMER` — internal-only audience; leaving them risks fail-open):

| Role                                                 | Persona emphasis                                           |
| ---------------------------------------------------- | ---------------------------------------------------------- |
| `ROLE_SERVICE_ADVISOR`                               | customer-facing tone; workorder/estimate/appointment focus |
| `ROLE_TECHNICIAN`                                    | terse; job-card, parts, labor entries                      |
| `ROLE_DISPATCHER`                                    | scheduling, queue, assignment trade-offs                   |
| `ROLE_LOCATION_MANAGER`                              | branch throughput, staffing, exceptions                    |
| `ROLE_ACCOUNT_MANAGER` / `ROLE_ACCOUNTING_ASSOCIATE` | audit-aware, posting-precise, reconciliation               |
| `ROLE_ADMIN` / `ROLE_SYSTEM_ADMINISTRATOR`           | governance, blast-radius, approval/audit callouts          |
| `ROLE_USER` (fallback)                               | generic safe persona                                       |

### 2.4 Conversation state model

Workflow state (§5/#778) governs _tool gating_. Separately, the **conversation** needs explicit lifecycle state so clarification loops, pending plans, intent changes, and expiry are handled deterministically rather than emergently. Extend the NLTI request/intent lifecycle to these states:

| State                  | Meaning                                    | Transitions                                           |
| ---------------------- | ------------------------------------------ | ----------------------------------------------------- |
| `READY`                | intent parsed, no action pending           | → NEEDS_CLARIFICATION, PENDING_CONFIRMATION, COMPLETE |
| `NEEDS_CLARIFICATION`  | missing required info; question posed      | → READY (answered), CANCELLED                         |
| `PENDING_CONFIRMATION` | write plan previewed, awaiting user yes/no | → CONFIRMED, CANCELLED, EXPIRED                       |
| `CONFIRMED`            | user approved; not yet executing           | → EXECUTING                                           |
| `EXECUTING`            | execution in progress                      | → COMPLETE, ERROR                                     |
| `COMPLETE`             | finished successfully                      | terminal                                              |
| `CANCELLED`            | user abandoned / replaced the plan         | terminal                                              |
| `EXPIRED`              | pending plan aged out before confirmation  | terminal (requires re-plan)                           |
| `ERROR`                | execution or system failure                | terminal                                              |

Behavior rules (must be explicit, not emergent):

- **Intent change mid-plan.** If a `PENDING_CONFIRMATION` plan exists and the user changes a material argument ("actually make it tomorrow"), **invalidate** the old plan (→ CANCELLED) and create a fresh preview. Never silently mutate a pending plan.
- **Multiple pending actions.** At most one `PENDING_CONFIRMATION` plan per session at a time; a new action supersedes (cancels) the prior pending plan unless the user confirms it first.
- **Session expiration.** `pos.nlti.session.ttl-hours` (24) bounds the session; pending plans expire far sooner (§2.5).
- **Clarification loops** use `NEEDS_CLARIFICATION` → bounded retries; after N unanswered clarifications, abandon to `CANCELLED` with a graceful message.

(`NltiIntentStatus` already has `READY|NEEDS_CLARIFICATION|PENDING_CLARIFICATION`; `NltiRequestStatus` needs `PENDING_CONFIRMATION`, `CONFIRMED`, `EXECUTING`, `CANCELLED`, `EXPIRED` added to the current `ACCEPTED|COMPLETE|ERROR`.)

### 2.5 Write-action confirmation gate

Leverages existing NLTI types — minimal new state.

1. **T1 router** classifies `NltiIntentType=ACTION` → always route to **T2-complex**.
2. **Executor produces a PLAN, not an execution.** Resolve the target tool + fully-grounded args + a human-readable summary. Emit `NltiAuditEventType.PLAN`. No write tool fires.
3. Persist the plan against the `NltiRequest` with an **idempotency key**, set state `PENDING_CONFIRMATION`.
4. User confirms → emit `CONFIRMATION` → execute the **exact persisted args** (never re-parse on confirm — re-parsing is the documented gate-mismatch failure mode; see `docs/runbooks/confirmation-gate-mismatch.md`).
5. Emit `EXECUTION_STEP` … `EXECUTION_COMPLETE` / `EXECUTION_FAILED`. Set request `COMPLETE`/`ERROR`.
6. **Permission re-check at BOTH plan time and execute time** — a cached agent must not let a lower-permission caller execute a higher-permission write (#779).

#### 2.5.1 Pending-plan expiry & replacement

- **Expiry:** pending write plans expire after `mcp.nlti.write.plan-ttl` (default **10 minutes**) or when the user changes any material argument. Confirmation after expiry → `EXPIRED`; requires regenerating the plan.
- **Idempotency-key reuse:** the same key may execute **once**. A confirmed-and-executed key is terminal; a re-sent confirmation is a no-op returning the original result, not a second write.
- **Cancel/replace:** a new action in the same session cancels the prior `PENDING_CONFIRMATION` plan (§2.4).

#### 2.5.2 Argument provenance (required for writes)

Every tool argument is tagged with its source:

`USER_TEXT | RETRIEVED_DOC | PRIOR_TOOL_RESULT | USER_CONTEXT | INFERRED_DEFAULT | SYSTEM_CONFIG`

Rule: **every write-plan argument carries a provenance marker. Any argument with `INFERRED_DEFAULT` provenance must be visibly disclosed in the preview** ("defaulting quantity to 1 — change?"). Inferred defaults on high-risk writes should be prohibited outright, not silently applied.

#### 2.5.3 Business-rule validation is not bypassed

> Confirmation authorizes execution of the proposed API call; it does **not** bypass service-layer validation, permission checks, approval workflows, accounting controls, inventory controls, or concurrency checks. The downstream service remains the authority and may still reject the call.

#### 2.5.4 Concurrency & stale-data handling

ERP/service data changes between preview and confirm (inventory, pricing, workorder status). Protect against acting on stale state:

- Capture entity **version / ETag** at plan time where the source API exposes one.
- **Re-read before write** for `NltiRiskLevel≥MEDIUM`; if the source record changed since plan time, force a **re-preview** rather than executing.
- Surface the change to the user ("price changed from $X to $Y since you asked — re-confirm?").

#### 2.5.5 High-risk confirmation strength

> `HIGH`-risk actions require stronger confirmation — such as typed confirmation, manager approval, or integration with an existing step-up authentication mechanism **if one is available**.

This avoids creating an implicit hard dependency on MFA infrastructure. Typed confirmation is the always-available baseline; step-up auth is optional and only if the platform already provides it.

---

## 3. RAG knowledge plan

### 3.1 Current corpus

6 preloaded docs (`application.yml` `mcp.rag.preload.docs`): accounting (double-entry), inventory control, HR functions, shop-management (×2), security-service. Good _domain-concept_ coverage; gaps below for intuitiveness and for the admin audience.

### 3.2 Documents to add

| Priority | Doc                                                                                                                                      | rag-scope                  | Min permission                 | Chunking        | Purpose                                                                                                                  |
| -------- | ---------------------------------------------------------------------------------------------------------------------------------------- | -------------------------- | ------------------------------ | --------------- | ------------------------------------------------------------------------------------------------------------------------ |
| P1       | **"What can I ask" capability catalog**                                                                                                  | per-role sections          | AUTHENTICATED (section-tagged) | 500             | Grounds the assistant's self-description; reduces false "I can't do that". Biggest perceived-intuitiveness gain per doc. |
| P1       | **Cross-domain workflow playbooks** (estimate→approval→workorder→parts→labor→invoice→payment; PO→receive→reconcile; warranty/claim flow) | master                     | AUTHENTICATED                  | 1500/200        | Teaches the seams between silos — serves the orchestration goal of one synthesized answer.                               |
| P1       | **Glossary + identifier-format doc** (workorder#, SKU, VIN, invoice#, PO#, account/claim codes; abbreviations/synonyms)                  | master                     | AUTHENTICATED                  | **500 (small)** | Dense retrieval is weak on exact codes; pairs with hybrid BM25 (§4.3). Small chunks → one term/code per retrieval unit.  |
| P2       | **Order / Pricing / Tax domain docs**                                                                                                    | order, pricing, tax        | domain read perms              | 1500/200        | Prompts for these domains exist with **no backing RAG doc**.                                                             |
| P2       | **Customer / Vehicle / Catalog docs** (identity, fitment, variants)                                                                      | customer, vehicle, catalog | domain read perms              | 1500/200        | Same gap; needed for fitment/identity grounding.                                                                         |
| P2       | **Reporting metric definitions**                                                                                                         | reporting                  | reporting read perm            | 1500/200        | Defines metrics so the `reporting` persona interprets, not guesses.                                                      |
| P2       | **Governance & approval-gate doc**                                                                                                       | admin                      | **admin perm**                 | 1500/200        | Feeds `admin` persona; what needs approval, audit implications, blast-radius.                                            |
| P2       | **Observability / event-tracing doc** (event types, reconstructing entity history)                                                       | events                     | audit/observability perm       | 1500/200        | Feeds `events` persona + runbooks.                                                                                       |
| P3       | **Role→permission catalog matrix**                                                                                                       | master / security          | **admin/security perm**        | 800             | Answers "why can't I do X" / "who can approve Y" without guessing. Extends `security-service-guide.md`.                  |

### 3.3 RAG visibility filtering — permission-first

**Filtering is permission-aware, not role-only.** A document carries a `min-permission` (or permission-set) metadata tag; `RoleAwareMetadataFilter` admits a doc only when the caller's `permissionCodes` satisfy it. Role may be used as a _convenience hint_ for scope selection but is **never the sole access gate**.

Rationale: a technician with elevated admin permissions, or a manager temporarily acting in another capacity, must get access consistent with their _actual permissions_, not their nominal role. Role-only filtering would give inconsistent access for these cases.

Hygiene:

- Tag every doc with `rag-scope` AND `min-permission`. Admin/security/permission docs gate on admin perms; floor staff never retrieve them.
- Keep deterministic IDs (`accounting.de-bookkeeping` style) + content-hash supersede on change (#637).
- Small chunks (~500) for glossary/ID/permission docs; default 1500–2000/200 for prose.

---

## 4. Retrieval & embedding plan

### 4.1 Embedding model

`nomic-embed-text` (768-dim, ivfflat) is a fair baseline, weak on ERP jargon/codes. Candidates:

- **`bge-m3`** (1024-dim) — strong retrieval, native dense **and** sparse vectors → ideal partner for hybrid (§4.3). **Recommended.**
- `mxbai-embed-large` (1024-dim) — strong dense alternative.

**Cost of switching:** dimension 768 → 1024 means re-embed the entire corpus + bump `mcp.rag.dimension` + migrate the pgvector column + rebuild the index. Do it **once**, bundled with §4.3, never piecemeal.

### 4.2 Index

`ivfflat` is fine at current scale. Migrate to **HNSW** when doc/operation-tool count grows (better recall, higher build cost). Not urgent.

### 4.3 Hybrid retrieval (#784)

Highest-ROI retrieval change for this domain. Add a Postgres FTS / BM25 retriever and merge it into the existing Tier-2 hybrid+rerank stage. Exact-ID / part-number / invoice-number queries under-retrieve on pure dense. `bge-m3` supplies sparse vectors directly. **Gate behind the §6 harness** so the merge weighting is tuned against measured recall, not vibes.

---

## 5. Telemetry & event schema (added early — Phase 0/1, not Phase 7)

Dashboards/admin UI can come later (Phase 7), but the **event schema must exist from Phase 0/1** so every subsequent change is evaluable. Emit one structured telemetry event per request capturing:

- `correlationId`, `sessionId`, `primaryRole`
- **router decision** (intent type, risk level, domain, complexity)
- **selected model tier** + actual model name; **fallback model used?**
- **selected tools** + **tools rejected for permissions** (counts + names)
- **RAG docs retrieved** (ids + scores) + **prompt layers included**
- **write-risk level**, **confirmation outcome** (confirmed/cancelled/expired)
- **latency by tier** (T0/T1/T2), total p50/p95
- **unsupported-answer / low-grounding flag** where detectable

This is distinct from `nlti_audit_event` (compliance audit) and `mcp_tool_invocation_log` (tuning input) — it is the **evaluation/observability stream**. Lands as a schema + emitter in Phase 0; dashboards consume it in Phase 7.

---

## 6. Evaluation & guardrails (build this FIRST)

Without measurement, every later phase is a guess and adaptive tuning can silently regress.

### 6.1 Fixture sizes (minimums)

- **≥100 tool-selection fixtures** spread across major roles (advisor, technician, manager, accounting, admin).
- **≥50 RAG retrieval fixtures**, including exact IDs, misspellings, abbreviations, and cross-domain workflow questions.
- **≥30 write-action safety fixtures** (preview correctness, provenance disclosure, expiry, stale-data, permission re-check).
- Capture a **baseline** before any prompt/model/retrieval change.

### 6.2 Metrics & CI thresholds

- Tool selection: **hit@5**, **MRR**. RAG: **recall@k**.
- Write gate: plan args == executed args; permission re-check at execute; no mutation without `CONFIRMATION`; provenance present on all write args.
- **CI rule:** fail on _statistically meaningful_ regression, not single-score noise. Threshold language:
  > No change may reduce tool-selection **hit@5 by more than 2 percentage points** or reduce **MRR by more than 5%** without explicit approval.
- **Latency SLO:** #637's "30-second soft SLO"; keep ingestion off the request path; track p50/p95 per tier.

### 6.3 Adaptive tuning is controlled, not automatic

- Keep tuning **off** (`mcp.tuning.enabled=false`) until Phase 0 completes.
- After Phase 0, run tuning in **shadow mode**: compute the tuned ranking, log it against the live baseline, **do not apply**.
- Promote tuned priorities to live **only if the eval harness improves** (or holds within threshold). Never auto-promote silently.

---

## 7. Phased implementation plan (step by step)

Ordered so each phase stands on a verified base. Do not start a phase before the prior exit criterion is green. Each phase carries an explicit rollback.

### Phase 0 — Measurement, telemetry, config hygiene

- **Goal:** make every later change measurable; remove model ambiguity.
- **Do:** build the #783 harness (§6.1/6.2); add the telemetry event schema + emitter (§5); pick and document a deliberate default executor model; reconcile `application.yml` ↔ README (G9); set `mcp.tuning.enabled=false`.
- **Touches:** new test/eval module; telemetry emitter; `application.yml`; README.
- **Exit:** harness runs in CI with baseline numbers recorded; telemetry events emitted; one documented default model; tuning off.
- **Rollback:** measurement-only — revert config/test additions; no runtime behavior change to undo.
- **Issues:** #783.

### Phase 1 — Role-first layered prompts

- **Goal:** make the assistant _feel_ role-aware. Highest UX leverage, no schema change.
- **Do:** seed `ROLE_*` persona prompts (§2.3); implement layered assembly BASE+ROLE+DOMAIN+TOOL-USE; invert resolution to role-first; keep the 16 domain prompts as DOMAIN layer; ensure blocking ≡ streaming prompt resolution; emit prompt-layer telemetry.
- **Touches:** `SystemPromptSeedRunner`, `RolePromptResolverImpl`, `SystemPromptDefaults`, both session managers; README correction (G1).
- **Exit:** technician vs controller demonstrably get different personas for the same question; answer-quality eval ≥ baseline.
- **Rollback:** feature-flag role-first resolution; flip back to `promptNameForRagScope` if eval regresses. Seeded prompts are additive (idempotent upsert).
- **Issues:** #637 (prompt portion).

### Phase 2A — Shared blocking/streaming orchestration path

- **Goal:** one tool-selection path, one prompt path; fresh caches.
- **Do:** unify `SessionAgentManager` / `StreamingSessionAgentManager` selection + prompt resolution; fix role preload (include `ROLE_TECHNICIAN`, `ROLE_USER`); enforce cache TTL / explicit invalidation.
- **Touches:** both session managers; agent cache.
- **Exit:** identical tools + persona blocking vs streaming on fixtures; no cache older than TTL.
- **Rollback:** revert to per-manager paths (kept behind a flag during cutover).
- **Issues:** #639.

### Phase 2B — Permission-gating cleanup + security sentinel

- **Goal:** authorization explained solely by permissions + workflow state.
- **Do:** drop legacy role gating (`mcp_role`, `mcp_tool_role`, `ToolRegistryRoleMapper`, role-gated queries) via Flyway; land #781 (`requiredPermissionsOperationCustomizer` → `pos-security-common`; `AUTHENTICATED` sentinel; transitional absent-extension = AUTHENTICATED) and #782 (role-default-permissions endpoint for cache prebuild).
- **Touches:** Flyway migrations; `ToolRegistryService`; `pos-security-common`; `pos-security-service`; ~18 services for sentinel emission (cross-team — sequence carefully).
- **Exit:** gating query references no role; selection fully explained by `permissionCodes ∩ mcp_tool_permission ∩ workflow state`.
- **Rollback:** migrations are destructive — **take a DB snapshot before drop**; keep legacy tables for one release as `*_deprecated` rather than immediate drop if rollback risk is high.
- **Issues:** #780, #781, #782.

### Phase 2C — Workflow state beyond IDLE

- **Goal:** activate non-IDLE tool sets.
- **Do:** persist workflow state on `NltiSession`; thread into `ToolSelectionContext`; preload non-IDLE tool sets (`CREATING_PO`, `PROCESSING_RETURN`, …); reconcile with the conversation-state model (§2.4).
- **Touches:** `NltiSession`, `ToolSelectionContext`, `ToolRegistryLoader`, both managers.
- **Exit:** a non-IDLE session receives that state's gated tool set (tested).
- **Rollback:** default workflow state back to `IDLE` via flag — selection degrades gracefully to current behavior.
- **Issues:** #778.

### Phase 3 — OpenAPI tool execution bridge

- **Goal:** assistant can call discovered operations with no facade.
- **Do:** LangChain4j `ToolProvider` serving `source='openapi'` candidates via `OperationProxyFactory`; wire into both managers; propagate `CurrentUserContext` permission codes into every proxied call.
- **Touches:** new `ToolProvider`; both session managers; discovery package.
- **Exit:** end-to-end test executes a discovered operation lacking a facade; lower-permission caller cannot reach a higher-permission tool via a shared cached agent.
- **Rollback:** disable the `ToolProvider` (flag) → falls back to facade-only tools.
- **Issues:** #779, #645.

### Phase 4 — Tiered model router

- **Goal:** small model for routing/simple, large for complex/writes — at controlled cost.
- **Do:** T1 router (intent/risk/domain/complexity, JSON, temp 0); executor selection by §2.2 rule; emit router/tier telemetry; keep `mcp.model.fallback` orthogonal.
- **Touches:** `IntentParserService`, `AgentOrchestrationService` / streaming counterpart, model config.
- **Exit:** ≥80% of simple queries served by T2-simple; answer-quality eval ≥ Phase 1; p95 within SLO.
- **Rollback:** route everything to T2-complex (single-tier) via flag — higher cost, known-good quality.
- **Issues:** —.

### Phase 5 — RAG expansion, permission-aware filtering, hybrid retrieval

- **Goal:** broader, more retrievable knowledge; exact-code recall; correct visibility.
- **Do:** add P1/P2 docs (§3.2) with scope + `min-permission` tags; enforce permission-aware RAG filter (§3.3); migrate embeddings to `bge-m3` (1024) + add BM25 hybrid (§4.3) in one migration.
- **Touches:** `application.yml` preload list; new RAG docs; `RagConfiguration`, retrieval factories, `RoleAwareMetadataFilter`; Flyway (dimension + index).
- **Exit:** recall@k improvement on harness; admin-only docs never returned to non-admin fixtures.
- **Rollback:** embedding migration is one-way — **snapshot the embedding table**; keep prior 768 column until the 1024 path is validated, then drop.
- **Issues:** #784.

### Phase 6 — Write-action confirmation gate

- **Goal:** safe gated writes. Highest risk → last, on a solid base.
- **Do:** §2.4 + §2.5 in full — conversation states, PLAN/CONFIRMATION/EXECUTION\_\* flow, `PENDING_CONFIRMATION`/`CONFIRMED`/`EXECUTING`/`CANCELLED`/`EXPIRED` statuses, idempotency key, plan expiry, argument provenance, stale-data re-preview, dual permission check, risk-tuned confirmation strength, WRITE-GATE prompt layer.
- **Touches:** `NltiRequestService`, `NltiRequestStatus`, orchestration services, `AuditLedgerService`, controllers; `confirmation-gate-mismatch.md` runbook.
- **Exit:** all §6.1 write fixtures green; plan args == executed args; provenance disclosed; expiry + stale-data enforced; no mutation without confirmation; audit chain complete.
- **Rollback:** flip the interface to **read-only** (suppress write tools) — fully safe degradation.
- **Issues:** —.

### Phase 7 — Admin tooling, dashboards, tuning controls

- **Goal:** operability + curation.
- **Do:** admin endpoints for `mcp_tool_permission` (#785), audited, TTL-bounded; dashboards/alerts consuming the §5 telemetry; promote adaptive tuning from shadow → controlled live (§6.3); doc + runbook updates.
- **Touches:** new admin controller; `docs/dashboards/`, `docs/alerts/`, `docs/runbooks/`.
- **Exit:** permission mappings curatable at runtime; dashboards live; tuning promotion gated by evals.
- **Rollback:** disable admin endpoints; revert tuning to shadow.
- **Issues:** #785.

---

## 8. Open decisions / risks

- **Embedding migration is a one-way, whole-corpus re-embed.** Schedule deliberately (Phase 5); snapshot first.
- **`qwen3.5:cloud` "cloud" tier** implies Ollama-hosted cloud inference — confirm it fits the cost/data-residency posture for the complex tier.
- **`AUTHENTICATED` sentinel rollout (#781) spans ~18 services** — Phase 2B is cross-team; sequence accordingly.
- **Legacy-table drop (#780/2B) is destructive** — prefer `*_deprecated` rename for one release if rollback risk is material.
- **Issue coverage caveat:** the review behind this plan covered repo issues #270–#785 (page 1 of 4). Lower-numbered NLTI/MCP issues, if any, are not yet folded in — pull the remaining pages before locking Phase 2+ scope.

---

## 9. Issue → phase map

| Phase | Issues           |
| ----- | ---------------- |
| 0     | #783             |
| 1     | #637 (prompts)   |
| 2A    | #639             |
| 2B    | #780, #781, #782 |
| 2C    | #778             |
| 3     | #779, #645       |
| 4     | —                |
| 5     | #784             |
| 6     | —                |
| 7     | #785             |
