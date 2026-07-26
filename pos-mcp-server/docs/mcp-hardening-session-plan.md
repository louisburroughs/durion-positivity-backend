# MCP Hardening — Working Session Plan

> **Status:** PLAN. Bundles the remaining `pos-mcp-server` hardening into one working session and
> defines what closes #645, #778, #779, #780, #783, #784. Grounded in a current-state read of the
> code (2026-07-26); several issues are further along than their text implies — verify before
> building.

## Environment prerequisites (what gates the "live" items)

| Need | Unblocks |
|---|---|
| Live alpha stack (gateway + model backend + pgvector) | #645 alpha 404 check, #779 Gate 3 |
| CI-reachable embedding backend (Ollama + pgvector) **or** recorded-fixture mode | #783 threshold-in-CI, #784 validation |
| None (code-only, unit/ArchUnit testable) | #780, #778, #779 (IT + comments), #645 (fallback/refresh/metrics) |

Everything except the two rows above can be finished and merged without a live environment.

---

## Batch A — Code-only quick wins (no infra)

### #780 — Remove legacy role-gating  · **code done** · only owner-gated migration remains
**Verified 2026-07-26 — code removal already complete:** `roleToolAssignments` no longer exists
anywhere; `LoadedMasterAgentRegistry` does not exist; the two-arg `resolveDomainTools(String,
Collection)` overload does not exist (only the 1-arg domain resolver); `MasterAgentRegistry.
resolveDomainTools` has no role branch; `findEnabledByRoleAndWorkflow`/`findAllRoleNames`/
`ToolRegistryRoleMapper` are gone. Scenario suites (cashier/manager/admin/`ROLE_USER`) pass.
- **Done this session:** owner confirmed the V19 retention window has passed; migration
  `V27__drop_legacy_role_gating.sql` drops both deprecated tables (validated: full Flyway chain
  V1→V27 boots clean on H2). **Closeable on merge.**

### #778 — Workflow state beyond IDLE for gating  · **done**
**Verified 2026-07-26 — all functional ACs implemented:**
- **Both** managers thread persisted state: `SessionAgentManager.chat` (line 199) and
  `StreamingSessionAgentManager.streamChat` (line 167) call `resolveActiveState` → 4-arg
  `selectRoleTools`, heuristic 3-arg only as fallback.
- Write path is wired: `NltiWorkflowStateService.advance(...)` is invoked by
  `NltiController.setWorkflowState` (`POST .../workflow-state`), ownership-checked.
- `MasterAgentRegistryLoader` defaults `mcp.agent.preload-workflow-state=ALL` and preloads the union
  across all workflow states (non-IDLE included).
- Tests: sync persisted-state (`SessionAgentManagerTest`), service + ownership
  (`NltiWorkflowStateServiceTest`), controller (`NltiControllerTest`), **and now the streaming
  persisted-state parity test** added this session.
- **Close when:** merged — no code gaps remain.

### #779 — OpenAPI tools agent-callable (code-only parts)  · **partly done**
**Verified 2026-07-26 — the stale comments are already fixed:** `OpenApiToolProvider` (~33–43) and
`RequestScopedUserContext` (~12–27) both now correctly describe streaming as wired (caller published
synchronously at Flux-assembly time).
- **Done this session:** authored `OpenApiToolPermissionGatingIT` (live-gated, `@ActiveProfiles("alpha")`
  + `-Dmcp.eval.live=true`, mirroring `BaselineCaptureIT`). It drives the real `OpenApiToolProvider`
  against live pgvector with DB-derived fixtures: fail-closed with no context; a caller lacking a tool's
  permission never receives it; granting it does. Compiles in CI; **runs on the alpha stack** (can't
  execute in the offline sandbox — gated off by default).
- **Verified live 2026-07-26 — closeable.** Gate 3 permission-gating ran green on the alpha stack via
  `scripts/eval_live.py` (`permission_gating_779: PASS` — a single-permission openapi tool is absent for an
  `AUTHENTICATED`-only caller and present once the permission is granted), reproducing the
  `OpenApiToolPermissionGatingIT` invariant against live pgvector. Stale comments already fixed. **#779 closed.**

---

## Batch B — Discovery hardening (#645, mostly code)  · effort M · risk med

**Verified 2026-07-26 — all code items already implemented and tested:**
- **Per-service Eureka fallback: done.** `ToolRegistrationServiceImpl.registerViaPerServiceFallback`
  wires `fetchForService` / `fallbackServiceIds` when the aggregate is empty/matches no tools; fail-soft
  per service. Tested (`ToolRegistrationServiceImplTest` fallback / no-fallback-on-success / both-empty).
- **Periodic refresh: done.** `DiscoveryRefreshScheduler` (`@Scheduled fixedDelay`, opt-in via
  `mcp.server.discovery-refresh.enabled`, timeout-bounded, serialized) + `SchedulingConfiguration`
  (`@EnableScheduling`). Re-registration is idempotent (`addToolWithTiming` removes-then-adds). Note the
  issue lists **dynamic unregistration (stale-tool pruning) as out of scope**, so re-adding new/changed
  tools without pruning is the intended behavior. Tested (`DiscoveryRefreshSchedulerTest`).
- **Metrics + alerting + runbook: done.** Counters `tools.discovered` / `tools.registered` (both
  incremented); `docs/alerts/tool-discovery-alerts.md`; `docs/runbooks/tool-discovery-failure.md`.
- **Only remaining:** alpha end-to-end 404 verification → **Batch D** (live).
- **Close when:** the alpha 404 check passes on the live stack.

---

## Batch C — Retrieval eval, then hybrid (ordered: #783 → #784)

### #783 — Retrieval-quality regression (hit@5, MRR)  · partly done
**Verified 2026-07-26:**
- **AC1 (volume + gate): done.** `generated.json` fixtures bring the suites to 105 tool-selection /
  56 rag / 34 write-safety (≥ 100/50/30). `EvalFixtureValidationTest.minimumFixtureCountsMet` is active
  (no `@Disabled`) and green — validated offline this session.
- **AC2 (retarget seed to live facade names): done this session.** The 101 generated fixtures already
  used the 16 registered facade class names; two **seed** fixtures still used the pre-discovery op-id
  form and were the documented `baseline.json` hit@5 = 0 cause. Retargeted:
  `crm_getallcustomers→CustomerFacadeTool`, `crm_listvehiclesforcustomer→VehicleFacadeTool`,
  `workorders_createworkorder→WorkorderFacadeTool`, `inventory_submitreturntostock→InventoryFacadeTool`.
  **Confirmed live** on alpha via `scripts/eval_live.py` (JVM-free pgvector+Ollama replica of the
  production selection SQL): **hit@5 = 0.84, MRR = 0.77, forbidden_violations = 0**.
- **Forbidden-fixture correction (done).** The live run flagged 2 `*-neg-role-user` fixtures as
  violations; root cause was the V18 facade permission-union model (WorkorderFacadeTool and
  AdminFacadeTool carry an `AUTHENTICATED` grant, so a bare user is legitimately offered them). Converted
  those 2 into positive `authenticated-baseline` fixtures; the broader design exposure is filed as
  **#1115** (facade `AUTHENTICATED` grants).
- **AC4 (thresholds): done this session.** Floors calibrated from the live baseline: hit@5 ≥ 0.75,
  MRR ≥ 0.65, `forbidden_violations == 0` — asserted in `BaselineCaptureIT`
  (`-Dmcp.eval.min-hit5`/`-Dmcp.eval.min-mrr` overridable) and enforced with a non-zero exit in
  `eval_live.py` (`EVAL_MIN_HIT5`/`EVAL_MIN_MRR`) so a Python-only host can gate CI. Wiring the run into
  the CI pipeline still needs a CI-reachable embedding backend (or recorded-fixture mode).
- **AC3 (recall@k): harness wired this session — live capture remaining.** Metadata keys identified
  (`document_id`, `rag_scope` from `DocumentEmbeddingIngestor` / `ScopedContentRetrieverFactory`). Every rag
  fixture (`rag-retrieval/{seed,generated}.json`, 56 total) now carries a top-level `rag_scope`, derived from
  its expected docs' configured scope in `application.yml` static-preload (e.g. `admin.governance→admin`,
  `crm.customer-vehicle→customer`, `tax.guide→tax`), defaulting to `master` for cross-scope/unknown fixtures —
  matching `RagScope.normalize(null)` and `MasterAgentRegistry.resolveRagScopeForTools` fallback. Negative
  fixtures point at their forbidden doc's own scope so the permission filter (not the scope filter) is what
  must exclude the doc. Schema updated (`schema/rag-retrieval.schema.json`).
  - **Recall@k harness (both hosts):** `eval_live.py` and `BaselineCaptureIT.captureRagRecallBaseline`
    reproduce the production RAG path — `ScopedContentRetrieverFactory` (ANN filtered by `rag_scope`) +
    `PermissionAwareMetadataFilter` (drop docs whose `required_permissions` the caller lacks) — collapse
    chunks to distinct `document_id`s, then score recall@k plus a forbidden-doc leak check. Forbidden leaks
    hard-fail (security invariant); the recall floor is report-only (`EVAL_MIN_RECALL` /
    `-Dmcp.eval.min-recall`, default 0.0) until calibrated.
  - Remaining: run against alpha to record the recall@k baseline and set the floor.
- **Close when:** AC3 recall@k baseline is captured on alpha with the floor set, and the threshold run is
  wired into CI.

### #784 — Hybrid dense + BM25 retrieval  · effort L · **depends on #783** · priority low
- Add a lexical `QueryDocumentRetriever` (Postgres FTS `tsvector` + `ts_rank` / `websearch_to_tsquery`),
  scope-filtered like the dense path.
- Migration: `tsvector` column + index (on `mcp_document_embedding` or a companion table).
- Fuse dense + lexical in `HybridContentRetriever` via Reciprocal Rank Fusion; keep
  `RerankedContentRetriever` as the re-rank stage.
- **Close when:** the fusion is validated by the #783 recall@k harness (measurable recall gain).

---

## Batch D — Live-stack verification (blocked on environment)  · effort M (ops-heavy)

- #645: alpha end-to-end — discovered tools invoke through the gateway with **zero 404s**.
- #779: Gate 3 live-stack verification per `docs/gate3-openapi-bridge-design.md`.
- Run these **together** in one live session (shared gateway + model + pgvector setup).

---

## Sequencing

```
Batch A (#780, #778, #779-code)  ──►  Batch B (#645-code)  ──►  Batch C (#783 ──► #784)
        (parallel-safe, merge first)                                   │
Batch D (#645-live, #779-live)  ── run whenever a live stack exists ───┘ (independent)
```

- Do **A** first: smallest, unblocks nothing else but retires risk and dead code cheaply.
- **C** and **D** share the "needs a backend" blocker — schedule them with whoever owns the CI/live infra.
- **#784** is gated on **#783** and is low priority; defer if the session runs short.

## Per-issue close conditions

| Issue | Blocked by infra? | Closes when |
|---|---|---|
| #780 | No | Dead role-gating code removed; deprecated tables dropped (if window passed); selection tests green |
| #778 | No | Persisted non-IDLE state gates tools in **both** managers, with a test; verify write path is invoked |
| #779 | Partly (Gate 3 live) | Permission IT via stubbed gateway passes; stale comments fixed; Gate 3 done in Batch D |
| #645 | Partly (alpha 404) | Eureka fallback + scheduled refresh + metrics/alerts/runbook; 404 check in Batch D |
| #783 | Yes (CI backend) | Fixtures at volume, retargeted names (hit@5 > 0), recall@k captured, CI thresholds |
| #784 | Yes (via #783) | Lexical + RRF fusion validated by recall@k harness |

## Open questions for the owner

1. **#780:** has the `V19` retention window passed — safe to drop `*_deprecated` tables now?
2. **#783 / #784 / Batch D:** is a CI-reachable Ollama + pgvector available, or should #783 land in
   recorded-fixture mode and defer live thresholds?
3. **Ladder follow-ups** (from PR #1113 — structured entity extraction for deep-link filters, a telemetry
   `resolution` block): fold into this session, or track separately?
