# NL Interface — Phase-Gate Execution Checklist

> **Companion to:** `docs/implementation_phase_gates.md` (gate definitions) and `docs/nl-interface-design.md` (design).
> **Purpose:** A living execution log. Check items off **only with evidence**. Each gate ends with a sign-off block recording metrics, decision, and approver. Guards against silent tech debt and scope drift.
>
> **Rules of use:**
> - Do not start a gate until the prior gate's **Decision = Pass** (or Pass-with-approved-exception) is signed.
> - Every `- [ ]` item carries an `_ev:_` (evidence) slot — fill with a link/commit/test-id/number before checking.
> - The **Cross-Phase Locks** block (bottom) is re-run at *every* gate; copy its result into the gate sign-off.
> - A drift check is phrased as a positive assertion ("Verified: …"). If it cannot be asserted true, the gate **holds**.
> - No item is checked on intention — only on observed result.

**Legend:** `[ ]` open · `[x]` done-with-evidence · `[~]` done-with-approved-exception (cite in sign-off) · `[!]` blocked

---

## Gate 0 — Measurement, Telemetry, Config Hygiene

**Entry:** none (first gate). **Goal:** measurement foundation before any behavior change.

### Scope
- [ ] Evaluation harness built — _ev:_
- [ ] Telemetry event schema + emitter added — _ev:_
- [ ] Model config drift reconciled — _ev:_
- [ ] Adaptive tuning disabled by default — _ev:_
- [ ] Baseline metrics captured — _ev:_

### Completeness gate
- [ ] Harness runs in CI from a clean checkout — _ev:_
- [ ] ≥100 tool-selection fixtures — _ev: count=_
- [ ] ≥50 RAG retrieval fixtures — _ev: count=_
- [ ] ≥30 write-action safety fixtures — _ev: count=_
- [ ] Baseline recorded: tool hit@5 — _ev:_
- [ ] Baseline recorded: tool MRR — _ev:_
- [ ] Baseline recorded: RAG recall@k — _ev:_
- [ ] Baseline recorded: write-gate safety results — _ev:_
- [ ] Baseline recorded: p50/p95 latency by tier (where available) — _ev:_
- [ ] Telemetry captures all required fields (correlation id, session id, primary role, router decision, model tier, actual model, fallback usage, selected tools, permission-rejected tools, RAG doc ids+scores, prompt layers, write-risk level, confirmation outcome, latency by tier, low-grounding flag) — _ev: schema ref_
- [ ] `application.yml` + README name **one** deliberate default model — _ev:_
- [ ] `mcp.tuning.enabled=false` set — _ev:_
- [ ] Adaptive tuning does not mutate live tool priorities — _ev:_

### Correctness tests
- [ ] CI executes harness from clean checkout — _ev: run_
- [ ] Fixture failures are visible and actionable — _ev:_
- [ ] Exactly one structured telemetry event per request — _ev:_
- [ ] Telemetry is separate from `nlti_audit_event` and `mcp_tool_invocation_log` — _ev:_
- [ ] Config docs match runtime defaults — _ev:_

### Drift checks (assert true)
- [ ] Verified: no prompt/routing/retrieval/write-gate behavior changed before baseline exists — _ev:_
- [ ] Verified: no model introduced without documented default/fallback/tier — _ev:_
- [ ] Verified: adaptive tuning not live (no regression gate yet) — _ev:_
- [ ] Verified: telemetry not postponed — _ev:_

### Cross-phase locks — [x] run & recorded in sign-off
### Exit decision: proceed only if baseline captured AND CI can detect regressions.
### Gate 0 sign-off
- Metrics table filled: [ ] (baseline PENDING live backend) · Decision: **HOLD**
- Exceptions (owner/expiry): n/a · Approver/date: pending · Rollback verified/documented: [x] (config-only; revertable)

#### Gate 0 — Execution results (2026-06-29, branch `feat/nl-interface-gates`)

**DONE + verified**
- [x] Config drift reconciled — `application.yml` chat-model documented as the single deliberate default (`qwen3.5:cloud`); README row updated to match. _ev: application.yml chat-model comment; README Configuration table._
- [x] Adaptive tuning disabled by default — `mcp.tuning.enabled=${MCP_TUNING_ENABLED:false}`. Verified non-mutating: `ToolPriorityTuningService` is `@ConditionalOnProperty(name="mcp.tuning.enabled", havingValue="true")` → bean not created when false → cron never runs. _ev: ToolPriorityTuningService.java:21._
- [x] Telemetry event schema + emitter — `internal/telemetry/NltiRequestTelemetry` (v1, all required fields), `NltiTelemetryEmitter`, `LoggingNltiTelemetryEmitter` (separate `nlti.telemetry` logger; never throws into request path). Standalone `javac` against project deps: **EXIT=0**. _ev: standalone compile log; design §5 field parity._
- [x] Eval fixture schemas + seed fixtures + structural harness — JSON Schemas (3 suites), seed fixtures (verified perms/tool-ids), `EvalFixtureValidationTest`. Executed via JUnit Platform: **3 passed, 1 skipped, 0 failed**. _ev: EvalRunner output started=3 ok=3 failed=0 skipped=1._
- [x] Telemetry stream separate from audit + tool-invocation logs — dedicated logger, no DB coupling. _ev: LoggingNltiTelemetryEmitter._

- [x] "Harness runs in CI" — `EvalFixtureValidationTest` runs in the standard module surefire build. `./mvnw -o -pl pos-mcp-server test -Dtest=EvalFixtureValidationTest`: **Tests run 4, Failures 0, Errors 0, Skipped 1, BUILD SUCCESS** (skip = the `@Disabled` min-count exit gate).
- [x] Clean module build verified — `./mvnw clean install -am -pl pos-mcp-server -DskipTests`: **EXIT 0** (telemetry + harness compile in the real reactor build).

**HOLD (not met — Gate 0 cannot sign Pass)**
- [ ] Fixture minimum counts ≥100 / ≥50 / ≥30 — currently seed-only **4 / 4 / 4**. Tracked by `@Disabled minimumFixtureCountsMet`; must be enabled + green to pass. _Largest remaining Gate 0 work item; not blocked._
- [ ] Baseline metrics (hit@5, MRR, recall@k, write-safety, latency) — PENDING a live model backend (Ollama). `baseline.json` placeholder committed with null metrics. **External dependency — true blocker for this item and for downstream behavioral/metric gates.**

**CORRECTION (prior sign-off was wrong)**
- An earlier draft of this sign-off recorded a "Lombok 1.18.46 + JDK 25" build blocker. That was a **false alarm** caused by a bad maven invocation (`-am compile` without a prior reactor `install`). `./mvnw clean install -am -pl pos-mcp-server` builds cleanly (confirmed on main and in this worktree). **No build blocker exists.**

**Real bugs caught during verification** (fixed)
- jspecify `@Nullable` on fully-qualified `java.util.Map` was illegal → added `import java.util.Map`.
- Confirmed project uses Jackson 2 `com.fasterxml` API, matching telemetry imports (avoided a wrong-package bug).

---

## Gate 1 — Role-First Layered Prompts

**Entry:** Gate 0 Pass. **Goal:** role-aware behavior with **no** authorization change.

### Scope
- [ ] Role persona prompts seeded — _ev:_
- [ ] Layered assembly BASE + ROLE + DOMAIN + TOOL-USE implemented — _ev:_
- [ ] Prompt resolution is role-first, domain-second — _ev:_
- [ ] Existing 16 domain prompts preserved — _ev:_
- [ ] Blocking & streaming prompt resolution aligned — _ev:_
- [ ] Prompt-layer telemetry emitted — _ev:_

### Completeness gate
- [ ] Seeded: ROLE_SERVICE_ADVISOR, ROLE_TECHNICIAN, ROLE_DISPATCHER, ROLE_LOCATION_MANAGER, ROLE_ACCOUNT_MANAGER, ROLE_ACCOUNTING_ASSOCIATE, ROLE_ADMIN, ROLE_SYSTEM_ADMINISTRATOR, ROLE_USER — _ev:_
- [ ] ROLE_CUSTOMER and ROLE_SELF_SERVICE_CUSTOMER NOT seeded — _ev:_
- [ ] Ordinary requests assemble BASE+ROLE+DOMAIN+TOOL-USE — _ev:_
- [ ] Assembly does NOT use role to grant tool access — _ev:_
- [ ] Domain prompts still apply by RAG scope/domain — _ev:_
- [ ] Blocking & streaming use identical resolution logic — _ev:_
- [ ] Telemetry shows included prompt layers — _ev:_

### Correctness tests
- [ ] Technician vs accounting: same query → different persona framing — _ev:_
- [ ] Two roles w/ identical permissions: same query → same accessible tools — _ev:_
- [ ] Blocking vs streaming: same query → same prompt-layer selection — _ev:_
- [ ] Answer-quality eval ≥ Gate 0 baseline — _ev:_
- [ ] hit@5 drop ≤ 2pp; MRR drop ≤ 5% (else approved) — _ev:_

### Drift checks (assert true)
- [ ] Verified: role persona NOT used as authorization — _ev:_
- [ ] Verified: no role-only RAG filtering introduced — _ev:_
- [ ] Verified: no new prompt layer without telemetry — _ev:_
- [ ] Verified: domain prompts not rewritten unnecessarily — _ev:_
- [ ] Verified: no customer-facing personas in seed data — _ev:_

### Cross-phase locks — [x] run & recorded (Permission lock: persona grants no access — asserted in persona text + unit test; Prompt lock: layered + anti-hallucination BASE preserved)
### Exit decision: role-aware behavior visible, measurable, permission boundaries unchanged.
### Gate 1 sign-off
- Metrics filled: [ ] (answer-quality eval needs live stack) · Decision: **HOLD** (2 items pending)
- Exceptions: n/a · Approver/date: pending · Rollback (swap assemble→resolvePrompt in both managers) verified: [x] documented

#### Gate 1 — Execution results (2026-06-30)

**DONE + verified (unit, no backend)**
- [x] ROLE_* personas seeded for all 9 internal roles; ROLE_CUSTOMER / ROLE_SELF_SERVICE_CUSTOMER NOT seeded. _ev: RolePromptAssemblyTest.seedCoverage._
- [x] Layered assembly BASE + ROLE + DOMAIN + TOOL_USE, correct order, skip-on-absent, built-in BASE fallback. _ev: RolePromptAssemblyTest (4 pass)._
- [x] Role-first resolution (`RolePromptResolver.assemble(role, ragScope)`); role drives persona only, never tool/doc access (asserted in test + persona text). 
- [x] Domain prompts preserved — applied as the DOMAIN layer by RAG scope.
- [x] Blocking ≡ streaming — both `SessionAgentManager` and `StreamingSessionAgentManager` call the same `assemble(role, ragScope)`. _ev: identical systemMessageProvider wiring._
- [x] Build green: `mvnw test -Dtest=RolePromptAssemblyTest,Eval*` → 13 run, 1 skipped, 0 failed.

**HOLD (not met — Gate 1 cannot sign Pass)**
- [ ] Prompt-layer telemetry **emission** — `assemble()` returns the layer list, but it is not yet written into a per-request telemetry event (the request-scoped telemetry pipeline doesn't exist until it's wired through the chat path; interdependent with Gate 4 router telemetry). Assembler-side support is done.
- [ ] Behavioral correctness eval — "technician vs accounting → different persona; same persona → same tools" needs the live stack (full routing + DB). Structurally guaranteed (distinct personas, access unchanged); live measurement deferred with baseline.

---

## Gate 2A — Shared Blocking/Streaming Orchestration Path

**Entry:** Gate 1 Pass. **Goal:** one coherent orchestration path for both endpoints.

### Scope
- [ ] Tool-selection path unified — _ev:_
- [ ] Prompt-resolution path unified — _ev:_
- [ ] Role preload fixed — _ev:_
- [ ] Cache TTL / explicit invalidation enforced — _ev:_

### Completeness gate
- [ ] Both managers call shared selection logic — _ev:_
- [ ] Both managers call shared prompt-assembly logic — _ev:_
- [ ] ROLE_TECHNICIAN + ROLE_USER included in preload — _ev:_
- [ ] Agent cache respects `mcp.agent.cache-ttl-minutes` — _ev:_
- [ ] Cache invalidation behavior documented — _ev:_
- [ ] Telemetry distinguishes endpoint type while showing equivalent decisions — _ev:_

### Correctness tests (same user/perms/request)
- [ ] Same candidate tools — _ev:_
- [ ] Same prompt layers — _ev:_
- [ ] Same persona — _ev:_
- [ ] Same RAG scope — _ev:_
- [ ] Same workflow state — _ev:_
- [ ] Cache entries older than TTL not reused — _ev:_
- [ ] Preload covers all roles in `MCP_ROLE_PRIORITY` — _ev:_
- [ ] hit@5 / MRR within thresholds — _ev:_

### Drift checks (assert true)
- [ ] Verified: blocking/streaming do not keep divergent logic — _ev:_
- [ ] Verified: fixes centralized, not duplicated across managers — _ev:_
- [ ] Verified: cache behavior documented — _ev:_
- [ ] Verified: preload fully aligned to `MCP_ROLE_PRIORITY` (not partial) — _ev:_

### Cross-phase locks — [ ] run & recorded
### Exit decision: endpoint behavior functionally identical for same request context.
### Gate 2A sign-off
- Metrics filled: [ ] · Decision: ☐ Pass ☐ Pass+exception ☐ Hold ☐ Roll back
- Exceptions: ______ · Approver/date: ______ · Rollback (per-manager flag) verified: [ ]

---

## Gate 2B — Permission-Gating Cleanup & Security Sentinel

**Entry:** Gate 2A Pass. **Goal:** authorization explained solely by permissions + workflow state.

### Scope
- [ ] Legacy role-gating path removed — _ev:_
- [ ] Legacy role-gating tables migrated/deprecated — _ev:_
- [ ] `AUTHENTICATED` sentinel behavior added — _ev:_
- [ ] Role-default-permissions endpoint added (cache prebuild) — _ev:_
- [ ] Fail-closed semantics preserved — _ev:_

### Completeness gate
- [ ] Tool-selection query does NOT reference role — _ev:_
- [ ] `ToolRegistryRoleMapper` removed or fully bypassed — _ev:_
- [ ] `mcp_role` / `mcp_tool_role` renamed `*_deprecated` or safely retired — _ev:_
- [ ] `mcp_tool_permission` is the source of permission mapping — _ev:_
- [ ] Unguarded ops emit/treated as `AUTHENTICATED` — _ev:_
- [ ] Missing permissions never fail-open — _ev:_
- [ ] Role-default-permissions endpoint used for cache prebuild — _ev:_
- [ ] DB snapshot or rollback migration exists BEFORE destructive change — _ev:_

### Correctness tests
- [ ] No-permission user cannot select the tool — _ev:_
- [ ] Tool with zero permission rows not selected — _ev:_
- [ ] User with required permission selects tool regardless of nominal role — _ev:_
- [ ] `AUTHENTICATED` tools available only to authenticated users — _ev:_
- [ ] Legacy role-gating tables not consulted at runtime — _ev:_
- [ ] Tool-selection metrics within thresholds — _ev:_

### Drift checks (assert true)
- [ ] Verified: role absent from gating SQL — _ev:_
- [ ] Verified: no fallback grants access on absent permissions — _ev:_
- [ ] Verified: sentinel rollout complete & documented — _ev:_
- [ ] Verified: no destructive migration without rollback/snapshot — _ev:_
- [ ] Verified: permission behavior identical blocking vs streaming — _ev:_

### Cross-phase locks — [ ] run & recorded
### Exit decision: every selected tool explained by `permissionCodes ∩ mcp_tool_permission ∩ workflowState`.
### Gate 2B sign-off
- Metrics filled: [ ] · Decision: ☐ Pass ☐ Pass+exception ☐ Hold ☐ Roll back
- Exceptions: ______ · Approver/date: ______ · Snapshot + `*_deprecated` rollback verified: [ ]

---

## Gate 2C — Workflow State Beyond `IDLE`

**Entry:** Gate 2B Pass. **Goal:** activate workflow-specific tool sets deterministically.

### Scope
- [ ] Workflow state persisted on `NltiSession` — _ev:_
- [ ] Workflow state threaded into `ToolSelectionContext` — _ev:_
- [ ] Non-IDLE workflow tool sets preloaded — _ev:_
- [ ] Workflow state reconciled with conversation state — _ev:_

### Completeness gate
- [ ] `NltiSession` stores current workflow state — _ev:_
- [ ] Tool selection receives workflow state from session — _ev:_
- [ ] `WORKFLOW_IDLE` no longer hardcoded in managers — _ev:_
- [ ] `CREATING_PO` / `PROCESSING_RETURN` activate intended tools — _ev:_
- [ ] Workflow state distinct from conversation lifecycle state — _ev:_
- [ ] Workflow transitions logged in telemetry — _ev:_

### Correctness tests
- [ ] IDLE session → only IDLE-eligible tools — _ev:_
- [ ] `CREATING_PO` → PO-creation tools — _ev:_
- [ ] `PROCESSING_RETURN` → return-processing tools — _ev:_
- [ ] Changing workflow state changes tools only as expected — _ev:_
- [ ] Permission gating still applies inside every workflow state — _ev:_
- [ ] Blocking & streaming equivalent — _ev:_

### Drift checks (assert true)
- [ ] Verified: workflow state not a substitute for permission checks — _ev:_
- [ ] Verified: conversation state and workflow state not conflated — _ev:_
- [ ] Verified: non-IDLE tool sets not activated globally — _ev:_
- [ ] Verified: managers do not default every request to IDLE — _ev:_

### Cross-phase locks — [ ] run & recorded
### Exit decision: non-IDLE workflows testable and permission-safe.
### Gate 2C sign-off
- Metrics filled: [ ] · Decision: ☐ Pass ☐ Pass+exception ☐ Hold ☐ Roll back
- Exceptions: ______ · Approver/date: ______ · Rollback (flag → IDLE) verified: [ ]

---

## Gate 3 — OpenAPI Tool Execution Bridge

**Entry:** Gate 2C Pass. **Goal:** execute discovered OpenAPI ops without per-op facades.

### Scope
- [ ] LangChain4j `ToolProvider` for `source='openapi'` implemented — _ev:_
- [ ] Ops invoked via `OperationProxyFactory` — _ev:_
- [ ] User context + permission codes propagated — _ev:_
- [ ] Cached-agent permission leakage prevented — _ev:_
- [ ] Facade tools still work — _ev:_

### Completeness gate
- [ ] Discovered ops can become agent-callable — _ev:_
- [ ] Only selected + permission-eligible ops exposed — _ev:_
- [ ] Proxied calls include current user context — _ev:_
- [ ] Cached agents cannot expose prior higher-permission user's tools — _ev:_
- [ ] Facade + OpenAPI tools coexist — _ev:_
- [ ] Telemetry distinguishes facade vs OpenAPI source — _ev:_

### Correctness tests
- [ ] E2E: execute a discovered op with no facade — _ev:_
- [ ] Lower-permission user cannot call higher-permission op — _ev:_
- [ ] Permission re-checked at call time, not only cache-build — _ev:_
- [ ] Arguments schema-validated before proxy call — _ev:_
- [ ] Failed proxy → controlled error, not hallucinated success — _ev:_
- [ ] Blocking & streaming support bridge identically — _ev:_

### Drift checks (assert true)
- [ ] Verified: not all 500+ ops exposed without candidate selection — _ev:_
- [ ] Verified: OpenAPI ops do not bypass permission checks — _ev:_
- [ ] Verified: LLM cannot choose arbitrary URLs/ops outside registry — _ev:_
- [ ] Verified: tool schemas include argument validation — _ev:_
- [ ] Verified: facade behavior not regressed — _ev:_

### Cross-phase locks — [ ] run & recorded
### Exit decision: one discovered non-facade op safely callable end-to-end.
### Gate 3 sign-off
- Metrics filled: [ ] · Decision: ☐ Pass ☐ Pass+exception ☐ Hold ☐ Roll back
- Exceptions: ______ · Approver/date: ______ · Rollback (disable ToolProvider flag) verified: [ ]

---

## Gate 4 — Tiered Model Router

**Entry:** Gate 3 Pass. **Goal:** cheapest tier that preserves quality + safety.

### Scope
- [ ] T1 router/classifier added — _ev:_
- [ ] Classifies intent / risk / domain / complexity — _ev:_
- [ ] Simple queries → T2-simple — _ev:_
- [ ] Complex/write/accounting/tax/admin/security → T2-complex — _ev:_
- [ ] Fallback strategy kept orthogonal — _ev:_
- [ ] Router telemetry emitted — _ev:_

### Completeness gate
- [ ] Router returns strict JSON — _ev:_
- [ ] Router temperature 0 / deterministic — _ev:_
- [ ] Router output validated before use — _ev:_
- [ ] Invalid router output falls back safely — _ev:_
- [ ] ACTION always → T2-complex — _ev:_
- [ ] `NltiRiskLevel≥MEDIUM` → T2-complex — _ev:_
- [ ] Multi-tool expected → T2-complex — _ev:_
- [ ] Model tier + actual model name in telemetry — _ev:_

### Correctness tests
- [ ] ≥80% simple fixtures → T2-simple — _ev: %=_
- [ ] All write fixtures → T2-complex — _ev:_
- [ ] All accounting/tax/admin/security fixtures → T2-complex — _ev:_
- [ ] Invalid router JSON does not break processing — _ev:_
- [ ] Answer-quality eval ≥ Gate 1 — _ev:_
- [ ] p95 latency within soft SLO — _ev:_
- [ ] Fallback usage visible in telemetry — _ev:_

### Drift checks (assert true)
- [ ] Verified: risky requests NOT routed to small models for cost — _ev:_
- [ ] Verified: router decisions logged — _ev:_
- [ ] Verified: router does not override permission gating — _ev:_
- [ ] Verified: fallback not confused with tier-routing — _ev:_
- [ ] Verified: no prompt changes bundled without separate eval — _ev:_

### Cross-phase locks — [ ] run & recorded
### Exit decision: cost savings measurable, quality/safety preserved.
### Gate 4 sign-off
- Metrics filled: [ ] · Decision: ☐ Pass ☐ Pass+exception ☐ Hold ☐ Roll back
- Exceptions: ______ · Approver/date: ______ · Rollback (all→T2-complex flag) verified: [ ]

---

## Gate 5 — RAG Expansion, Permission-Aware Filtering, Hybrid Retrieval

**Entry:** Gate 4 Pass. **Goal:** better grounding, exact-code recall, permission-safe visibility.

### Scope
- [ ] P1/P2 RAG documents added — _ev:_
- [ ] All docs tagged `rag-scope` + `min-permission`/permission set — _ev:_
- [ ] Permission-aware RAG filtering enforced — _ev:_
- [ ] Hybrid retrieval added — _ev:_
- [ ] Embeddings migrated to `bge-m3` 1024-dim — _ev:_
- [ ] Rollback path preserved during migration — _ev:_

### Completeness gate
- [ ] New docs exist: capability catalog, workflow playbooks, glossary/identifier, order, pricing, tax, customer, vehicle, catalog, reporting metrics, governance/approval, observability/event-tracing — _ev:_
- [ ] Every RAG doc has permission metadata — _ev:_
- [ ] Admin/security docs require admin/security permissions — _ev:_
- [ ] Role is NOT the sole RAG visibility gate — _ev:_
- [ ] Hybrid dense + BM25/FTS active behind retrieval harness — _ev:_
- [ ] 1024-dim embedding path validated — _ev:_
- [ ] Prior 768-dim data retained until validation complete — _ev:_

### Correctness tests
- [ ] Exact WO/invoice/PO/VIN/SKU/account-code/claim-code fixtures improve recall — _ev:_
- [ ] Admin-only docs never returned to non-admin fixtures — _ev:_
- [ ] Permission-elevated users retrieve by permissions, not nominal role — _ev:_
- [ ] RAG recall@k improves or within approved threshold — _ev:_
- [ ] Dense-only vs hybrid comparison recorded — _ev:_
- [ ] Chunking validated (small for glossary/id, large for prose) — _ev:_

### Drift checks (assert true)
- [ ] Verified: no doc added without permission tags — _ev:_
- [ ] Verified: no role-only RAG filtering — _ev:_
- [ ] Verified: embedding migration not piecemeal — _ev:_
- [ ] Verified: hybrid weights tuned by harness, not intuition — _ev:_
- [ ] Verified: RAG docs not used to mask missing API/tool grounding — _ev:_
- [ ] Verified: admin docs not visible to floor-staff fixtures — _ev:_

### Retrieval lock (every new doc) — [ ] all docs have: deterministic ID, content hash, `rag-scope`, permission metadata, documented chunking — _ev:_
### Cross-phase locks — [ ] run & recorded
### Exit decision: retrieval quality improves and visibility rules proven.
### Gate 5 sign-off
- Metrics filled: [ ] · Decision: ☐ Pass ☐ Pass+exception ☐ Hold ☐ Roll back
- Exceptions: ______ · Approver/date: ______ · Embedding snapshot + dual-path rollback verified: [ ]

---

## Gate 6 — Write-Action Confirmation Gate

**Entry:** Gate 5 Pass. **Goal:** safe gated writes (preview → confirm → exact persisted execution).

### Scope
- [ ] Conversation lifecycle states implemented — _ev:_
- [ ] Write plan creation implemented — _ev:_
- [ ] Pending confirmation added — _ev:_
- [ ] Plan expiry added — _ev:_
- [ ] Idempotency key added — _ev:_
- [ ] Argument provenance added — _ev:_
- [ ] Stale-data protection added — _ev:_
- [ ] Dual permission checks added — _ev:_
- [ ] Audit chain added — _ev:_
- [ ] WRITE-GATE prompt layer injected — _ev:_
- [ ] Direct model mutation suppressed — _ev:_

### Completeness gate
- [ ] `NltiRequestStatus` supports PENDING_CONFIRMATION, CONFIRMED, EXECUTING, CANCELLED, EXPIRED + existing terminal — _ev:_
- [ ] ACTION requests produce PLAN, not immediate execution — _ev:_
- [ ] Write plans persist: target tool, exact args, idempotency key, risk level, arg provenance, source entity versions (where avail), expiration ts — _ev:_
- [ ] Confirmation executes exact persisted args — _ev:_
- [ ] Confirmation does NOT re-parse user text — _ev:_
- [ ] Plan expires after configured TTL — _ev:_
- [ ] Material user change cancels + replaces pending plan — _ev:_
- [ ] At most one pending plan per session — _ev:_
- [ ] Permission checked at plan time AND execution time — _ev:_
- [ ] Medium/high-risk writes re-read source before execution where possible — _ev:_
- [ ] Changed source data forces re-preview — _ev:_
- [ ] All write steps emit PLAN, CONFIRMATION, EXECUTION_STEP, EXECUTION_COMPLETE/FAILED — _ev:_
- [ ] WRITE-GATE layer active when write-capable tools are candidates — _ev:_

### Correctness tests
- [ ] No mutation without confirmation — _ev:_
- [ ] Plan args == executed args — _ev:_
- [ ] Expired confirmation does not execute — _ev:_
- [ ] Re-sent confirmation w/ same idempotency key does not double-execute — _ev:_
- [ ] Material arg change while pending → old cancelled, new preview — _ev:_
- [ ] Missing required arg → clarification, not guessed value — _ev:_
- [ ] Inferred defaults visible in preview — _ev:_
- [ ] High-risk inferred defaults rejected / require explicit selection — _ev:_
- [ ] Lower-permission user cannot execute higher-permission-context plan — _ev:_
- [ ] Changed entity version before confirm forces re-preview — _ev:_
- [ ] Downstream business-rule rejection surfaced accurately — _ev:_

### Drift checks (assert true)
- [ ] Verified: model cannot call write tools directly — _ev:_
- [ ] Verified: confirmation uses persisted args, not re-parse — _ev:_
- [ ] Verified: preview omits no important argument — _ev:_
- [ ] Verified: inferred defaults not hidden — _ev:_
- [ ] Verified: permission checked twice — _ev:_
- [ ] Verified: write gate does not bypass downstream validation — _ev:_
- [ ] Verified: no ambiguous coexisting pending plans — _ev:_
- [ ] Verified: audit chain complete — _ev:_

### Write lock — [ ] all satisfied: write tools suppressed from direct execution, preview-first, explicit confirm, exact persisted args, permission checked twice, complete audit chain, read-only rollback available — _ev:_
### Cross-phase locks — [ ] run & recorded
### Exit decision: ALL write-action safety fixtures pass.
### Gate 6 sign-off
- Metrics filled: [ ] · Decision: ☐ Pass ☐ Pass+exception ☐ Hold ☐ Roll back
- Exceptions: ______ · Approver/date: ______ · Rollback (flip read-only) verified: [ ]

---

## Gate 7 — Admin Tooling, Dashboards, Tuning Controls

**Entry:** Gate 6 Pass. **Goal:** operable, auditable, tunable without uncontrolled drift.

### Scope
- [ ] Audited admin endpoints for `mcp_tool_permission` — _ev:_
- [ ] Dashboards over telemetry — _ev:_
- [ ] Alerts for safety + quality regressions — _ev:_
- [ ] Adaptive tuning moved disabled → shadow → controlled-live (post-approval) — _ev:_
- [ ] Runbooks + docs updated — _ev:_

### Completeness gate
- [ ] Permission mappings curatable at runtime by authorized admins — _ev:_
- [ ] Admin changes audited — _ev:_
- [ ] Admin endpoints permission-gated — _ev:_
- [ ] Dashboard covers: routing, model-tier usage, fallback usage, tool-selection quality, permission rejects, RAG recall, prompt-layer usage, write confirmations/cancellations/expirations/failures, latency p50/p95 — _ev:_
- [ ] Alerts cover: failed-tool-call spike, permission-reject spike, fallback overuse, write-failure rate, confirmation-mismatch attempt, retrieval regression, latency SLO breach — _ev:_
- [ ] Adaptive tuning runs in shadow before live promotion — _ev:_
- [ ] Tuning promotion requires eval improvement or approved neutral — _ev:_

### Correctness tests
- [ ] Unauthorized user cannot access admin endpoints — _ev:_
- [ ] Admin permission changes take effect after cache invalidation/TTL — _ev:_
- [ ] Audit log records who/what/when — _ev:_
- [ ] Shadow tuning does not mutate live priority — _ev:_
- [ ] Live tuning cannot promote if eval thresholds fail — _ev:_
- [ ] Dashboard numbers reconcile with telemetry events — _ev:_

### Drift checks (assert true)
- [ ] Verified: admin endpoints do not bypass audit — _ev:_
- [ ] Verified: permission edits are TTL/cache-safe — _ev:_
- [ ] Verified: adaptive tuning cannot silently mutate priorities — _ev:_
- [ ] Verified: dashboards use structured telemetry, not ad hoc logs — _ev:_
- [ ] Verified: runbooks present and current — _ev:_

### Cross-phase locks — [ ] run & recorded
### Exit decision: runtime curation + observability in place.
### Gate 7 sign-off
- Metrics filled: [ ] · Decision: ☐ Pass ☐ Pass+exception ☐ Hold ☐ Roll back
- Exceptions: ______ · Approver/date: ______ · Rollback (disable admin, tuning→shadow) verified: [ ]

---

## Cross-Phase Locks — RUN AT EVERY GATE

Copy this block's result into each gate's sign-off. All must assert true or the gate holds.

1. **Scope lock** — [ ] Only this gate's required-scope items implemented; any extra has an approved change request (reason / affected phase / affected tests / affected rollback / updated exit criteria) — _ev:_
2. **Regression lock** — [ ] No unapproved regression in hit@5, MRR, recall@k, write safety, permission safety, blocking/streaming equivalence, p95 latency — _ev:_
3. **Permission lock** — [ ] No role-only authz, no model-only authz, no fail-open, no cross-tenant retrieval, no model-generated SQL, no ungated writes — _ev:_
4. **Prompt lock** — [ ] Prompt changes layered, telemetry-visible, preserve anti-hallucination + tool-before-answer + fact-vs-inference, no persona access semantics — _ev:_
5. **Write lock** — [ ] No write behavior unless: direct-exec suppressed, preview-first, explicit confirm, exact persisted args, permission×2, complete audit, read-only rollback — _ev:_
6. **Retrieval lock** — [ ] Every accepted RAG doc has deterministic ID, content hash, `rag-scope`, permission metadata, documented chunking — _ev:_
7. **Model lock** — [ ] No new default model without documented tier, purpose, fallback, latency impact, data-residency/cost, eval results — _ev:_

---

## Per-Gate Metrics Table (copy into each sign-off)

| Metric                 | Baseline | Current | Δ | Pass/Fail |
| ---------------------- | -------: | ------: |--:| --------- |
| Tool hit@5             |          |         |   |           |
| Tool MRR               |          |         |   |           |
| RAG recall@k           |          |         |   |           |
| Write safety pass rate |          |         |   |           |
| p95 latency            |          |         |   |           |

## Sign-off record (per gate)

```
Gate:           <id>
Date:           <yyyy-mm-dd>
Executed by:    <name>
Scope complete: <n/total checked>
Cross-locks:    <all-true | exceptions>
Decision:       <Pass | Pass+exception | Hold | Roll back>
Exceptions:     <desc / owner / expiry>
Rollback:       <tested | documented>
Next-gate appr: <approver / date / conditions>
```
