# pos-mcp-server — Spring AI Issues Delivery Plan

> **Status:** ACTIVE. Minimal-wave delivery plan for the open MCP-server issues
> (#645, #778–#785), re-grounded against the current **Spring AI** architecture
> (LangChain4j removed; in-process ChatClient / ToolCallback orchestration, pgvector
> RAG, Ollama models). Each issue body was updated 2026-07-24 to match code reality;
> this doc sequences the remaining work.

## Why these issues moved

The nine issues were authored against the pre-migration LangChain4j design and the
now-removed spec docs (`tool-usage-enhancement-spec.md`, the permission-based
tool-selection spec, the optimization guide, `nlq-to-api-second-review.md`). Those
specs were consolidated into the module `README.md` and the `docs/gate*` design docs.
Verification against the code (2026-07-24) found three issues far more complete than
their text implied.

| Issue | Title (short) | Verified state | Canonical doc |
|---|---|---|---|
| #645 | Aggregate OpenAPI discovery | Partial — discovery live (PR #646); fallback/refresh/metrics/alpha open | README "OpenAPI-discovered tools", `gate3-openapi-bridge-design.md` |
| #779 | OpenAPI tools agent-callable | **Implemented** under Spring AI (`OpenApiToolProvider`→`ToolCallback`); e2e + Gate 3 verify + comment cleanup remain | `gate3-openapi-bridge-design.md` |
| #778 | Workflow-state tool gating | Partial — `V20`/`WorkflowState`/engine overload exist; persisted state inert, managers use message heuristics | README "Known limitation — workflow state" |
| #780 | Retire legacy role-gating | Mostly done — `V19` renamed tables to `_deprecated`; role queries/mapper gone; dead code + table-drop remain | README backlog |
| #781 | AUTHENTICATED sentinel + customizer | Partial — sentinel emitted but copy-pasted in 20 `OpenApiConfig.java`; not promoted; mcp-server doesn't parse `x-required-permissions` | README "Tool Selection" |
| #782 | Role-default-permissions endpoint | Not started | ADR-0040 / ADR-0025 |
| #783 | Retrieval-quality tests (hit@5/MRR) | Partial — metrics/fixtures/driver exist; seed-only, no threshold gate, no RAG recall@k | `phase0-fixtures-and-telemetry.md` |
| #784 | Hybrid embedding + BM25 | Not started — retrieval is dense+dense ("hybrid" = query-expansion, no lexical) | `gate5-rag-hybrid-design.md` |
| #785 | Admin tooling for `mcp_tool_permission` | Largely done — RBAC REST surface exists; only `@EmitEvent` audit missing | `gate7-admin-observability-design.md` |

## Wave structure

Only two constraints force ordering:

1. **#783's recall@k harness gates #784** (BM25 merge is validated by recall@k).
2. **Two verifications need a running alpha stack** — #779 Gate 3 and #645 alpha 404.

Everything else is code-landable now, so the set collapses to **two waves**.

### Wave 1 — code-landable now

Grouped by owning module so cross-module items parallelize; within `pos-mcp-server`,
items are sequenced to avoid file conflicts.

| Issue | Work | Module(s) |
|---|---|---|
| #779 | Fix stale "not-yet-wired" comments (`OpenApiToolProvider`, `RequestScopedUserContext`); add e2e `*IT`: low-perm caller → chat → assert high-perm openapi tool not offered/executed, authorized op executes via stubbed gateway | pos-mcp-server |
| #785 | `@EmitEvent` (write/approval) on `ToolPermissionController` grant + revoke; register event ids in the module `EventTypes` registry + initializer | pos-mcp-server |
| #780 | Remove empty `roleToolAssignments` field + branch from `MasterAgentRegistry.resolveDomainTools`; drop the never-matching `resolveDomainTools(String, Collection)` overload; empty-map constructors in `LoadedMasterAgentRegistry`. (Table `DROP` deferred per `V19`.) | pos-mcp-server |
| #778 | Thread persisted `NltiSession.workflowState` into `ToolSelectionContext` from both managers via the `WorkflowState` overload; add a write path advancing state to non-IDLE; `MasterAgentRegistryLoader` preloads active non-IDLE states; test | pos-mcp-server |
| #645 | Wire `OpenApiDocumentFetcher.fetchForService` as per-service Eureka fallback on empty aggregate; `@Scheduled` periodic refresh + re-registration diff; `tools_discovered_total`/`tools_registered_total` Micrometer counters + alert rules + runbook | pos-mcp-server |
| #783 | Fixtures to ≥100/50/30, retarget to live facade tool names, RAG recall@k live capture in `BaselineCaptureIT`, threshold assertions wired into CI | pos-mcp-server (test) |
| #781 | Promote one `requiredPermissionsOperationCustomizer` to `pos-security-common`; delete the 20 per-service copies. In mcp-server discovery, parse `x-required-permissions`→auto-populate `mcp_tool_permission` (absent ⇒ `AUTHENTICATED`), replacing the `V18` hand-seed; document the transitional rule | ~18 services + pos-security-common + pos-mcp-server |
| #782 | `pos-security-service` endpoint returning role→default permission codes (backed by `RoleAuthorityService.expandRolesToAuthorities`); mcp-server client; real per-role sets in `prebuildRoleAgents` | pos-security-service + pos-mcp-server |

**Intra-wave coordination (not a separate wave):** #781's auto-populate of
`mcp_tool_permission`, #785's admin edits, and the `V18` hand-seed all touch the same
table. Whoever lands the auto-seed reconciles it against `V18` and the admin path.

### Wave 2 — dependent + live-stack

| Issue | Work | Gated on |
|---|---|---|
| #784 | Lexical `QueryDocumentRetriever` (Postgres FTS `tsvector`/`ts_rank`); `tsvector` column + index migration; RRF merge in `HybridContentRetriever`; keep re-rank stage | #783 recall@k harness |
| #779 | Gate 3 end-to-end verification | running model + gateway |
| #645 | Alpha zero-404 verification | running gateway |

## Priority note

Pre-production-critical subset: **#645, #778, #779, #780, #781, #782**.
Quality / low-priority follow-ups: **#783, #784, #785**.

## References

- Module `README.md` — authoritative architecture.
- `docs/gate3-openapi-bridge-design.md`, `docs/gate5-rag-hybrid-design.md`,
  `docs/gate7-admin-observability-design.md`, `docs/phase0-fixtures-and-telemetry.md`.
- `docs/spring-ai-big-bang-migration-checklist.md` — the LangChain4j → Spring AI cutover.
- ADR-0040, ADR-0025, ADR-0042, ADR-0021.
