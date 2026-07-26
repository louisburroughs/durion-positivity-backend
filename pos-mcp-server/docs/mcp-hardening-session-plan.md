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

### #780 — Remove legacy role-gating  · effort S · risk low
- Remove the always-empty `roleToolAssignments` field + branch in `MasterAgentRegistry.resolveDomainTools`
  (~line 61) and the empty-map constructors in `LoadedMasterAgentRegistry`.
- Drop the unused role-scoped `resolveDomainTools(String, Collection)` overload (`MasterAgentRegistry`
  ~125–135, `ToolSelectionEngine` ~112–115).
- New migration `V27` dropping `mcp_role_deprecated` / `mcp_tool_role_deprecated` — **only if the V19
  retention window has passed** (owner confirm).
- **Close when:** dead code gone, cashier/manager/admin/`ROLE_USER` selection tests + ArchUnit green.

### #778 — Workflow state beyond IDLE for gating  · effort S–M · risk low-med
**Verify first — largely implemented already:** `SessionAgentManager.chat` (line 199) threads persisted
state via `workflowStateService.resolveActiveState(username)` → 4-arg `selectRoleTools(role, perms,
message, state)`, heuristic 3-arg only as fallback; `NltiWorkflowStateService.setWorkflowState` (line 62)
is a real write path. Remaining:
- Confirm `StreamingSessionAgentManager.streamChat` threads persisted state the same way (parity with
  `SessionAgentManager`).
- Confirm the write path is actually **invoked on workflow progression** (find/complete the caller that
  advances `NltiSession.workflowState` to a non-IDLE state).
- `MasterAgentRegistryLoader` preloads the active **non-IDLE** states, not just the single
  `mcp.agent.preload-workflow-state`.
- **Close when:** a persisted non-IDLE session receives that state's gated tool set — tested for **both**
  managers.

### #779 — OpenAPI tools agent-callable (code-only parts)  · effort M · risk low
- Add an integration test (`*IT`): low-permission caller → chat endpoint → assert a high-permission
  openapi tool is neither offered nor executed, and an authorized op executes through a **stubbed
  gateway** (WireMock/local stub). Today the permission-filter assertion lives only at unit level.
- Fix stale comments in `OpenApiToolProvider` (~38–42) and `RequestScopedUserContext` (~20–25) that still
  claim the streaming/Reactor path is "not yet wired" — it is (`StreamingSessionAgentManager` sets the
  ThreadLocal synchronously before resolution).
- **Close when:** the IT passes and comments are corrected. (Gate 3 live verification → Batch D.)

---

## Batch B — Discovery hardening (#645, mostly code)  · effort M · risk med

- Wire `OpenApiDocumentFetcher.fetchForService(serviceId)` (exists, no prod caller) into
  `ToolRegistrationServiceImpl.registerDiscoveredTools()` as the **per-service Eureka fallback** when the
  aggregate fetch returns empty.
- `@Scheduled` periodic refresh that re-runs discovery and diffs against currently-registered tools
  (configurable interval); must be idempotent (no duplicate/rogue re-registration).
- Micrometer counters `tools_discovered_total` / `tools_registered_total`; alert rules under
  `docs/alerts/`; ops runbook section under `docs/runbooks/`.
- **Close when:** fallback + scheduled refresh land with tests, metrics emit, docs added. (Alpha 404
  check → Batch D.)

---

## Batch C — Retrieval eval, then hybrid (ordered: #783 → #784)

### #783 — Retrieval-quality regression (hit@5, MRR)  · effort M–L
- Author fixtures to volume (≥100 tool-selection / ≥50 rag / ≥30 write-safety); enable the `@Disabled`
  `minimumFixtureCountsMet` gate.
- Retarget seed fixtures to the **live facade tool names** (current mismatch → `baseline.json` hit@5 = 0).
- Implement RAG **recall@k live capture** in `BaselineCaptureIT` (`rag_recall_at_k` currently null).
- Threshold assertions (hit@5 / MRR / recall@k floors) wired into CI — **needs the embedding backend or a
  recorded-fixture mode**.
- **Close when:** fixtures at volume, hit@5 > 0 on retargeted names, recall@k captured, thresholds
  asserted in CI.

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
