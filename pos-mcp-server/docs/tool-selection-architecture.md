# Tool Selection & Runtime Configuration Architecture

Status: current as of #637 / #639 delivery. This document describes the behavior that is actually
implemented, including what is intentionally deferred. It supersedes the aspirational parts of the
earlier NLQ-to-API analyses referenced by issue #639.

## Scope

Covers the runtime path shared by blocking (`POST /v1/mcp/chat`) and streaming
(`POST /v1/mcp/chat/stream`) chat:

1. role resolution
2. system-prompt assembly (with fallback) — #637
3. permission/workflow-gated tool selection — #639
4. role-agent caching (TTL + invalidation) — #639
5. static RAG preload at startup — #637

## 1. Shared role resolution

Both chat controllers resolve the caller through the same components — there is no
controller-local role-priority logic:

- `McpRoleResolver` (`McpRoleResolverImpl`) picks the primary role from the caller's
  `ROLE_*` authorities using `SystemPromptDefaults.MCP_ROLE_PRIORITY`, falling back to
  `ROLE_USER` when no prioritized role is present.
- `CurrentUserContextResolver` wraps that into a `CurrentUserContext` (username, userId,
  primary role, all roles, authorities, permission codes) consumed by both orchestration
  services.

Startup agent prebuild covers `SystemPromptDefaults.PRELOADABLE_ROLE_IDENTIFIERS` —
`MCP_ROLE_PRIORITY` plus `ROLE_USER` — so `ROLE_TECHNICIAN` and `ROLE_USER` are never omitted.
Each prebuilt role's permission set is fetched from `pos-security-service`
(`RoleDefaultPermissionsClient`, fail-soft) so the warm cache matches the role's real gated tool
set; callers whose actual permission codes differ get a cache miss and an on-demand build.

## 2. Prompt assembly and fallback (#637)

`RolePromptResolverImpl.assemble(role, ragScope)` layers, in order:

| Layer | Source (`system_prompt.name`) | Missing behavior |
|---|---|---|
| BASE | `master` | built-in default text (`SystemPromptDefaults.DEFAULT_PROMPT_TEXT`) |
| ROLE | the role name, e.g. `ROLE_CASHIER` | layer skipped, warning + metric |
| DOMAIN | domain prompt keyed by RAG scope | layer skipped (silent; master scope has no DOMAIN layer) |
| TOOL_USE | built-in constant | always present |

`resolvePrompt(name)` precedence: requested prompt → `master` → built-in text.

Prompt records are managed only through the existing secured `SystemPromptController` CRUD API;
`SystemPromptSeedRunner` seeds role/domain prompts best-effort at startup.

Observability: every fallback past the requested prompt increments
`mcp.prompt.fallback{reason, requested}` with reasons `master-prompt`, `built-in`, or
`missing-role-layer`, alongside the existing warn logs.

## 3. Gated tool selection (#639)

`ToolSelectionEngine.selectRoleTools(role, permissionCodes, message[, workflowState])` is the
single selection contract used by **both** blocking and streaming orchestration — transport parity
is structural, not duplicated logic.

Order of operations inside `ToolRegistryService.resolveCandidateTools`:

1. **Gate first**: `findEnabledByPermissionsAndWorkflow(permissionCodes, workflowState)` — only
   tools the caller is authorized for in the active workflow state enter consideration.
2. **Admin fast path**: if the query matches admin keywords/phrases, is *not* vetoed by
   business-domain vocabulary, *and* `AdminFacadeTool` survived the gate, it is returned alone.
   This is the explicit confidence-based deterministic recovery path; it never bypasses
   permission gating.

   Because the fast path returns the admin tool **alone**, a false positive suppresses every
   other candidate for the whole request. Two guards keep that from firing on business
   questions: `account`/`accounts` are not bare keywords (they collide with "accounts
   receivable", "chart of accounts", "GL account" — the admin senses live in
   `ADMIN_QUERY_PHRASES` as "user account", "account state", …), and `FAST_PATH_VETO_TERMS`
   vetoes the path outright when finance/workorder vocabulary is present, so a mixed query
   ("who has access to the receivables ledger") still reaches semantic ranking.
3. **Gated semantic ranking**: ANN search is restricted to the gated set
   (`findTopKByEmbeddingForPermissions`), so unauthorized tools can never crowd authorized ones
   out of the candidate window. Candidates are scored (`semantic rank + priority − latency −
   cost`) and cut to `mcp.agent.candidate-tool-limit`.
4. **No-embedding fallback**: highest-priority gated tools by `priority`.
5. **Failure/empty fallback**: full role tool set (fail-open within the role's own domain tools,
   never beyond the caller's role scope).

Keyword fallback tools (web search / inventory / order facades) are merged in separately per
message, so short operational messages like `stock part 1234` still reach the inventory facade
even when semantic routing is weak.

### Workflow state

Authoritative state is the persisted `NltiSession` value (`NltiWorkflowStateService`); session-less
callers fall back to message-text heuristics. Implemented states: `IDLE`, `CREATING_PO`,
`RECEIVING_ASN`, `INVENTORY_RECON`, `PROCESSING_RETURN`. Heuristic derivation covers only the PO /
ASN / inventory-recon phrasings; anything else resolves to `IDLE`. Richer workflow-state
transitions (e.g. automatic state advancement from tool executions) are **intentionally deferred**
behind `NltiWorkflowStateService` — the selection contract already accepts the state as input, so
implementing them requires no orchestration changes.

## 4. Role-agent caching (#639)

Both agent managers (`SessionAgentManager`, `StreamingSessionAgentManager`, profile `alpha`):

- cache role agents keyed by `role::toolCacheKey` in Caffeine with
  `expireAfterWrite(mcp.agent.cache-ttl-minutes)` (default 30) and
  `maximumSize(mcp.agent.max-cached-agents)`;
- prebuild agents for the preloadable role set at startup (fail-soft per role);
- listen for `AgentCacheInvalidationEvent` and drop **all** cached role agents when runtime
  configuration changes. The event is published by:
  - `SystemPromptServiceImpl` on prompt create / update / delete, and
  - `ToolPermissionAdminService` on `mcp_tool_permission` grant / revoke.

  Listeners run `AFTER_COMMIT` (with fallback execution for non-transactional publishers), so a
  rebuild always re-reads committed configuration. Between a change and the next request there is
  no stale-serving window bounded by TTL anymore; TTL remains as the backstop for out-of-band
  changes (e.g. direct DB edits).

## 5. Static RAG preload (#637)

`RagPreloadRunner` (ApplicationRunner, non-test profiles) → `StaticRagPreloadService`:

- Documents are registered in `mcp.rag.preload.docs` (`StaticRagPreloadProperties`) with a
  **deterministic document id** (e.g. `accounting.de-bookkeeping`, `inventory.inv-cntrl`), a
  classpath source, a RAG scope, and optional required permissions.
- Content hash (SHA-256) is tracked per document id in `mcp_rag_preload_record`; unchanged
  content is skipped, changed content is re-ingested and **supersedes** prior embeddings via
  replace-on-document-id.
- Startup is best-effort: per-document failures are logged and recorded, never block boot.
- Metrics: `mcp.rag.preload.loaded` / `.skipped` / `.failed` (tagged by `documentId`) plus a
  preload duration timer.

Adding a new static document = adding one entry to `mcp.rag.preload.docs` and the markdown file
under `src/main/resources/rag/`; no code changes.

## Observability summary

| Signal | Meaning |
|---|---|
| `mcp.prompt.fallback{reason,requested}` | prompt resolution fell back (#639) |
| `mcp.rag.preload.loaded/skipped/failed{documentId}` | startup preload outcomes (#637) |
| `nlti.request.telemetry` events | per-request role, selected tools, prompt layers, workflow state, latency |
| "Invalidated MCP … role-agent cache" logs | configuration-triggered cache flush |
| Debug logs in `ToolRegistryService` / `ToolSelectionEngine` | per-request gating, scoring, fallback decisions |
