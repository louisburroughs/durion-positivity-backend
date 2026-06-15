---
title: Permission-Based Tool Selection Spec
description: Implementation-ready spec for replacing role-based MCP tool gating with perm_bits-derived permission gating, and for expanding the candidate tool pool to the full OpenAPI-discovered tool surface registered via the MCP Java SDK.
status: proposed
updated: 2026-06-11
---

This specification extends the gated-retrieval design in
`tool-usage-enhancement-spec.md` (now substantially implemented) along a new
axis. It replaces that design's role+workflow gating dimension with
permission-bit gating, and expands the `mcp_tool` candidate pool from the 16
hand-curated facade tools to the full tool surface already discovered from the
gateway's aggregate OpenAPI spec and registered with the MCP Java SDK.

## Background: How Tool Discovery Works Today

Before either change in this spec, it's important to be precise about how
`pos-mcp-server` currently learns about the ~100+ OpenAPI-discovered
operations referenced throughout this document. This is **not** classpath or
source-code scanning of the `pos-*` modules — it is an HTTP-fetch +
OpenAPI-model pipeline that runs at startup and populates the MCP Java SDK's
own tool registry:

1. `OpenApiDocumentFetcher.fetchAggregateSpec()` issues an HTTP GET (via the
   reactive `WebClient` bean `discoveryWebClient`) against
   `mcp.server.aggregate-spec-url` — the gateway's (`pos-api-gateway`)
   aggregated `/v3/api-docs` endpoint. The gateway's base URI is resolved
   through Eureka (`DiscoveryClient.getInstances("pos-api-gateway")`), with a
   `http://localhost:8080` fallback for local/dev.
2. The response body is parsed with `OpenAPIV3Parser`
   (`io.swagger.v3.parser`, `resolve=true`, `resolveFully=true`) into an
   `OpenAPI` model object — the same aggregate spec that backs the gateway's
   Swagger UI, already including every service's `x-required-permissions`
   extensions (added by each service's
   `OpenApiConfig.requiredPermissionsOperationCustomizer`).
3. `OpenApiToolMapper.toAggregateToolSpecifications(baseUri, openApi)` walks
   every path/operation in that model. For each operation it derives a
   `domain` (the first non-version path segment), a sanitized `toolName`
   (`{domain}_{operationId}`), a `displayName`/`description`, and an input
   schema, and builds an `McpSchema.Tool` (an MCP SDK type) plus a handler
   from `OperationProxyFactory` that performs the actual HTTP call against the
   gateway when the tool is invoked.
4. `ToolRegistrationServiceImpl.registerDiscoveredTools()` (triggered by
   `ToolBootstrapRunner`, an `ApplicationRunner`) registers each
   `McpSchema.Tool` via `mcpAsyncServer.addTool(...)` — the MCP Java SDK's
   tool-registry API — and then calls
   `mcpAsyncServer.notifyToolsListChanged()` so connected MCP clients refresh
   their tool list.

The result: "the complete list of tools available via the Java SDK" is
exactly the operation set present in the gateway's aggregate OpenAPI spec at
the time of the last successful fetch, refreshed at startup, with failures
swallowed (`onErrorResume` → `Mono.empty()`) so a gateway/discovery outage
never blocks `pos-mcp-server` startup.

This pipeline is **out of scope to change** for this spec. §9 and §10 extend
its *output* — persisting it into `mcp_tool`/`mcp_tool_permission` and
bridging it to the LangChain4j agents — but the fetch/parse/register steps
above remain as-is.

## Problem Statement

Today, `ToolRegistryService.resolveCandidateTools` gates the `mcp_tool`
candidate pool through the `mcp_tool_role` join, keyed on
`CurrentUserContext.primaryRole()` (resolved by `McpRoleResolver` and
normalized by `ToolRegistryRoleMapper`).

- A user's actual authorization is governed by `perm_bits` — the JWT-encoded
  permission bitset decoded by the gateway into `X-Perm-Bits`/`X-Perm-Ver`,
  expanded by `GatewayAuthoritiesFilter` into per-user granted authorities.
  Both `PERM_<code>` and bare `<code>` (e.g. `order:shipment:cancel`) forms are
  already present, unused, in `CurrentUserContext.authorities`.
- Roles are coarse, hardcoded permission expansions. A specific user's
  `perm_bits` can diverge from their role's "typical" set (custom grants,
  revocations, multi-role users, or `ROLE_USER` fallback). This produces two
  failure modes:
  - **Over-exposure**: a tool is offered (its name, description, and input
    schema shown to the model) for operations the caller's `perm_bits` do not
    actually authorize — wasted turns, confusing 403s, and disclosure of
    capability metadata for domains the caller cannot use.
  - **Under-recall**: a caller with grants outside their role's defaults
    cannot reach the matching tools, because `mcp_tool_role` never lists them
    for that role.
- Separately, only the 16 hand-curated `mcp_tool` facade rows participate in
  gated retrieval/selection for the LangChain4j chat agents
  (`SessionAgentManager`/`StreamingSessionAgentManager`). The aggregate
  OpenAPI spec exposes 100+ operations, all already registered with the MCP
  SDK's `McpAsyncServer` via `ToolRegistrationServiceImpl.registerDiscoveredTools()`,
  but none of them are visible to the chat agents.
- Each of those operations already carries an `x-required-permissions`
  extension — added by the `requiredPermissionsOperationCustomizer` present in
  every service's `OpenApiConfig` (verified across all 18 services with an
  `OpenApiConfig.java`) — listing the permission codes extracted from
  `@PreAuthorize(hasAuthority(...) / hasAnyAuthority(...))`. This data is
  already produced and already flows through the gateway aggregate spec; it is
  simply discarded by `OpenApiToolMapper`/`ToolRegistrationServiceImpl` today.

## Solution

Two coordinated, independently-shippable changes:

1. **Replace role+workflow gating with permission+workflow gating.** A tool
   becomes a selection candidate only if the caller's `perm_bits`-derived
   permission codes intersect a new `mcp_tool_permission` mapping for that
   tool. Tools with no recorded permission mapping are never returned
   (fail-closed). Workflow-state gating is retained unchanged as an orthogonal
   dimension.

2. **Expand the candidate pool to the full OpenAPI-discovered tool surface.**
   Upsert every operation discovered from the gateway aggregate spec into
   `mcp_tool` (carrying its `x-required-permissions` codes into
   `mcp_tool_permission` and a generated description embedding), and add an
   execution bridge so permission-gated, semantically-ranked OpenAPI tools
   become callable by the LangChain4j agents alongside the existing facade
   tools.

Role is **not removed from the system** — `CurrentUserContext.primaryRole()`
and `McpRoleResolver` continue to drive system-prompt selection, RAG scope,
and agent-cache namespacing. Role is removed only as a *tool-gating* signal.

## User Stories

1. As a cashier whose `perm_bits` include an extra ad hoc grant (e.g.
   `inventory:stock:adjust`) outside the cashier role's default set, I want
   the assistant to offer the matching inventory-adjustment tool, so that my
   actual entitlements — not my role's stereotype — determine what the
   assistant can do for me.

2. As a security reviewer, I want every tool surfaced to an agent to be backed
   by an explicit, auditable permission mapping derived from the same
   `@PreAuthorize` annotations that gate the underlying REST endpoint, so that
   "what the assistant can do" never silently exceeds "what the user is
   authorized to do".

3. As a service writer, I want the assistant to be able to use any of the
   ~100 operations discovered from the gateway's OpenAPI surface — not just
   the 16 hand-built facade tools — so that requests outside the curated
   facade set can still be fulfilled without a manual facade-tool addition.

4. As a platform engineer, I want a tool with zero recorded permission
   mappings to be excluded from candidate selection by default, so that newly
   discovered or newly added tools cannot be silently exposed before someone
   has reviewed and recorded their access requirements.

5. As an on-call engineer, I want logs/metrics that show, for a given request,
   which permission codes gated which tools in or out, so that "why didn't the
   assistant offer X" and "why did it offer Y" are both answerable without
   reading source code.

6. As two users sharing the same role but different `perm_bits`, I want the
   assistant's cached/shared agent infrastructure to never let one user's tool
   access bleed into the other's session, so that agent caching for
   performance never becomes a privilege-escalation vector.

## Implementation Decisions

### 1. Permission-code extraction in `CurrentUserContext`

- Add `@NonNull Set<String> permissionCodes` to `CurrentUserContext`,
  populated in `CurrentUserContextResolver.resolve()` by filtering
  `authorities` to entries that do **not** start with `ROLE_` or `PERM_` —
  i.e. the bare `domain:resource:action` codes that
  `GatewayAuthoritiesFilter.expandAuthority()` already adds alongside the
  `PERM_<code>` forms. Implement as a small static helper (e.g.
  `PermissionCodes.extract(Set<String> authorities)`) local to
  `pos-mcp-server`.
- Always include the synthetic code `AUTHENTICATED` in `permissionCodes` for
  any resolved context (see §7).

### 2. `ToolSelectionContext` gains `permissionCodes`, retains `role`

- Add `@NonNull Set<String> permissionCodes` to `ToolSelectionContext`.
- `role` is **retained**, but its contract changes: it no longer participates
  in `mcp_tool` gating. It remains the input to (a) `RolePromptResolver`/RAG
  scope selection, (b) agent-cache namespacing
  (`role + "::" + toolCacheKey(...)`), (c) the admin fast-path (reworked in
  §8), and (d) logging/telemetry. Document this contract change prominently on
  the record — it is easy to assume "role" still gates tools.

### 3. New schema: `mcp_tool_permission` (migration `V17__tool_permission_gating.sql`)

```sql
CREATE TABLE mcp_tool_permission (
    tool_id UUID NOT NULL REFERENCES mcp_tool(id) ON DELETE CASCADE,
    permission_code VARCHAR(150) NOT NULL,
    PRIMARY KEY (tool_id, permission_code)
);

CREATE INDEX idx_mcp_tool_permission_code ON mcp_tool_permission (permission_code);

ALTER TABLE mcp_tool ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'facade';
ALTER TABLE mcp_tool ADD COLUMN http_method VARCHAR(10);
ALTER TABLE mcp_tool ADD COLUMN path_template VARCHAR(500);
ALTER TABLE mcp_tool ADD COLUMN operation_id VARCHAR(150);
```

`source` distinguishes hand-curated facade beans (`'facade'`, resolved through
`MasterAgentRegistry`) from OpenAPI-discovered operations (`'openapi'`,
resolved through the execution bridge in §10). `http_method`, `path_template`,
and `operation_id` are populated only for `source = 'openapi'` rows and are
needed by the execution bridge.

### 4. Seed permission mappings for the 16 existing facade tools (migration `V18__seed_facade_tool_permissions.sql`)

Fail-closed means every existing facade tool needs at least one
`mcp_tool_permission` row before this ships, or it silently disappears from
candidate pools. Each facade tool's `@Tool`-annotated methods call specific
downstream REST operations; the permission codes for those operations are
already visible in the aggregate OpenAPI spec's `x-required-permissions`
extensions. This migration is one-time manual curation: enumerate each
`pos-mcp-server/.../orchestration/tools/*FacadeTool.java`'s underlying calls
and record the union of their required-permission codes (OR semantics — see
§6), including a mapping for `AdminFacadeTool` needed by the fast-path rework
in §8.

### 5. Replace role+workflow gating queries with permission+workflow gating queries

`ToolMetadataRepository`/`ToolMetadataRepositoryImpl`:

- Remove `findEnabledByRoleAndWorkflow`, `findTopKByEmbeddingForRole`, and
  `findAllRoleNames`.
- Add `findEnabledByPermissionsAndWorkflow(Set<String> permissionCodes, String workflowState)`
  and `findTopKByEmbeddingForPermissions(float[] embedding, int limit, Set<String> permissionCodes, String workflowState)`.
- SQL shape: `mcp_tool t JOIN mcp_tool_permission tp ON tp.tool_id = t.id`,
  filter `tp.permission_code = ANY(?)` (bind as `String[]` via
  `connection.createArrayOf("varchar", ...)`), join
  `mcp_tool_workflow`/`mcp_workflow_state` and filter `ws.name = ?`,
  `t.enabled = true`. Use `DISTINCT` on `t.id` since a tool may match multiple
  permission codes. The embedding variant keeps
  `ORDER BY t.embedding <=> ?::vector, t.id LIMIT ?`.
- An empty `permissionCodes` set must short-circuit to an empty result. (An
  empty `ANY('{}')` array correctly matches nothing in PostgreSQL, but cover
  it with an explicit test rather than relying on that incidentally.)

`ToolRegistryService.resolveCandidateTools`:

- Replace `ToolRegistryRoleMapper.normalize(context.role())` plus the
  role-gated repository calls with `context.permissionCodes()` and the new
  permission-gated methods.
- Keep the existing scoring pipeline (`ToolScorer`: semantic rank + priority −
  latency penalty − cost penalty) and the priority-sorted no-embedding
  fallback unchanged — only the gating predicate changes.

### 6. Multi-permission tools: OR semantics, with a documented AND/OR limitation

`x-required-permissions` is a flat array produced by regex-extracting every
`'...'` token from merged `@PreAuthorize` annotations — it cannot distinguish
`hasAuthority('a') and hasAuthority('b')` (caller needs **both**) from
`hasAnyAuthority('a','b')` (caller needs **either**). For *gating* (not
enforcement), treat `mcp_tool_permission` membership as OR: a tool is a
candidate if the caller holds **any** of its mapped codes. This is a
deliberate recall-favoring default — the downstream `@PreAuthorize` check on
the actual REST call is unaffected and remains authoritative, so an
over-included candidate can still be rejected with a normal 403 if the caller
lacks an AND-combined permission.

Document this as a known limitation. If false-positive exposure becomes
measurable in practice, a follow-up could have the shared `OpenApiConfig`
customizer emit separate `x-required-permissions-all` /
`x-required-permissions-any` extensions plus a corresponding
`mcp_tool_permission.match_mode` column.

### 7. Fail-closed semantics and the `AUTHENTICATED` sentinel

Fail-closed means a tool with **zero** rows in `mcp_tool_permission` is never
a candidate, regardless of caller — correctly excluding unmapped/unreviewed
tools. But `requiredPermissionsOperationCustomizer` only adds
`x-required-permissions` when its regex finds at least one `'...'` token in
`@PreAuthorize`. An endpoint annotated `@PreAuthorize("isAuthenticated()")` (or
with no `@PreAuthorize` at all, relying on the security filter chain alone)
produces **no extension at all** — under naive fail-closed treatment this
would permanently exclude an entire class of legitimately-available,
authentication-only endpoints. That is an unintended under-recall regression,
not the over-exposure problem fail-closed is meant to solve.

Recommended fix: update `requiredPermissionsOperationCustomizer` (replicated
identically across all 18 `OpenApiConfig` classes — consider promoting it to a
shared `pos-security-common` utility while making this change) so that when
`extractRequiredPermissions()` returns empty *and* the merged `@PreAuthorize`
value is `isAuthenticated()` or absent, it emits
`x-required-permissions: ["AUTHENTICATED"]`. `pos-mcp-server`'s registration
pipeline (§9) maps this sentinel to a synthetic `mcp_tool_permission` row with
`permission_code = 'AUTHENTICATED'`, and `CurrentUserContextResolver` always
includes `'AUTHENTICATED'` in `permissionCodes` (§1). This keeps fail-closed
meaningful for genuinely permission-gated operations while not regressing
authenticated-only endpoints.

### 8. Admin fast-path rework

`ToolRegistryService`'s admin fast-path currently checks
`"ROLE_ADMIN".equals(ToolRegistryRoleMapper.normalize(context.role()))` plus a
keyword match to force-select `AdminFacadeTool`. Replace the role check with a
permission check: fast-path to `AdminFacadeTool` when
`context.permissionCodes()` intersects `AdminFacadeTool`'s
`mcp_tool_permission` rows (looked up the same way as any other candidate)
**and** the message matches the existing admin keyword set. This keys the
fast-path to the same authorization signal as everything else, and
automatically extends to any caller whose `perm_bits` happen to include
admin-tool permissions, regardless of role.

### 9. Full OpenAPI tool-surface registration into `mcp_tool`

Extend `ToolRegistrationServiceImpl.registerDiscoveredTools()`:

- `OpenApiToolMapper.toAggregateToolSpecifications()` already iterates every
  operation to build `McpSchema.Tool`s for `mcpAsyncServer.addTool(...)`.
  Extend it to also return, per operation, the fields needed for persistence:
  `domain`, `toolName`, `displayName`/`description`, HTTP `method`,
  `pathTemplate`, `operationId`, and the `x-required-permissions` list (read
  via `operation.getExtensions().get("x-required-permissions")`, falling back
  to `["AUTHENTICATED"]` per §7).
- After the existing `mcpAsyncServer.addTool(...)` registration loop
  (unchanged — this remains how raw MCP protocol clients see the full tool
  list), upsert each discovered operation into `mcp_tool`:

  ```sql
  INSERT INTO mcp_tool (id, name, display_name, description, domain, priority,
                         cost_level, avg_latency_ms, enabled, handler_bean,
                         source, http_method, path_template, operation_id)
  VALUES (?, ?, ?, ?, ?, 0.5, 'low', 400, true, 'openapi_proxy',
          'openapi', ?, ?, ?)
  ON CONFLICT (name) DO UPDATE SET
      display_name = EXCLUDED.display_name,
      description = EXCLUDED.description,
      domain = EXCLUDED.domain,
      http_method = EXCLUDED.http_method,
      path_template = EXCLUDED.path_template,
      operation_id = EXCLUDED.operation_id;
  ```

  then replace that tool's `mcp_tool_permission` rows
  (`DELETE ... WHERE tool_id = ?`, then re-`INSERT`) from the freshly-read
  `x-required-permissions`. `name` (the existing `{domain}_{operationId}`
  sanitized form, already unique via `uk_mcp_tool_name`) is the natural upsert
  key and is stable across restarts as long as operation IDs are stable.
- Default `priority`/`cost_level`/`avg_latency_ms` for `source = 'openapi'`
  rows are conservative placeholders (lower priority than hand-tuned facades,
  `cost_level = 'low'`, `avg_latency_ms = 400`); tune later from observed
  latency metrics.
- **Embeddings**: `ToolEmbeddingInitializer` already finds any `mcp_tool` row
  `WHERE embedding IS NULL` and embeds `description`. Newly-upserted
  `source = 'openapi'` rows are picked up automatically — but only if it runs
  *after* `registerDiscoveredTools()` completes. Today both are independent
  `ApplicationRunner`s with no defined ordering. Prefer triggering embedding
  population reactively at the end of `registerDiscoveredTools()` for the rows
  it just touched (correct on every re-registration, not just first boot), with
  an explicit `@Order` between the two runners as defense-in-depth.
- **Startup-cost note**: first run upserts and embeds ~100+ rows via the
  configured `EmbeddingModel` (Ollama). Because the no-embedding fallback path
  (priority-sorted gated tools) already exists and degrades gracefully,
  embedding population can run in the background without blocking server
  readiness — `notifyToolsListChanged()` and the `mcp_tool` upsert complete
  first; embeddings backfill over the following seconds, affecting only
  *semantic ranking* until populated, not tool *availability*.

### 10. Execution bridge for OpenAPI-discovered tools in LangChain4j agents

This is the highest-uncertainty piece, and the one most likely to need a short
technical spike before implementation. Verified against current LangChain4j
docs (`langchain4j-mcp`, not yet a `pos-mcp-server` dependency):

- `AiServices` supports mixing **static** tools (`.tools(...)`) with a
  **dynamic** `ToolProvider` (`.toolProvider(...)`) — both contribute to the
  merged tool set per invocation. The existing `.tools(tools)` call in
  `SessionAgentManager.buildAgent()`/`StreamingSessionAgentManager` does not
  need to be removed; a `ToolProvider` is added alongside it.
- `ToolProvider.provideTools(ToolProviderRequest)` is invoked **per agent
  invocation**, not once at build time, and `ToolProviderRequest` exposes
  `chatMemoryId()` and `userMessage()`. Because `provideTools()` runs
  synchronously on the calling request's thread during `agent.chat(...)`, a
  custom `ToolProvider` can read the **current** request's
  `SecurityContextHolder`/`CurrentUserContext` (populated per-request by
  `GatewayAuthoritiesFilter`) at invocation time — it does not need, and must
  not rely on, any state captured when a cached `PosAssistant` agent was
  originally built. This is the property that makes agent caching safe under
  permission-based gating (see Security Considerations).
- `langchain4j-mcp` provides `McpToolProvider`, wrapping one or more
  `McpClient`s (the existing unused `mcpSyncClient` bean is exactly this) with
  `filterToolNames(...)` (static name allow-list) and
  `filter(BiPredicate<McpClient, ToolSpecification>)`/`toolFilter(...)`
  (dynamic predicate, evaluated per `provideTools()` call; multiple filters
  AND together).

Recommended design — a `@Component` `PermissionGatedOpenApiToolProvider`
implementing `ToolProvider`:

1. On `provideTools(request)`, resolve the current request's
   `CurrentUserContext` (request-scoped bean, or a value threaded through by
   `SessionAgentManager` immediately before calling `agent.chat(...)` — verify
   this survives the reactive thread-handoff for streaming chat).
2. Reuse `toolRegistryService.resolveCandidateTools(...)` (same
   `permissionCodes`/`workflowState`/`candidateToolLimit` inputs as the facade
   path) filtered to `source = 'openapi'` rows, so OpenAPI tools and facade
   tools are selected from one coherent ranked pool, just resolved through two
   different execution paths.
3. For each selected `source = 'openapi'` `ToolMetadata`, build a
   `ToolSpecification` (name, description, input schema — sourced from the
   `McpSchema.Tool` already registered with `mcpAsyncServer`, fetched via
   `mcpSyncClient.listTools()` and cached) and a `ToolExecutor` calling
   `mcpSyncClient.callTool(new CallToolRequest(name, arguments))`. The
   `mcpSyncClient`'s transport must relay the caller's bearer token (the same
   `BearerTokenRelayInterceptor` used by `loadBalancedRestClientBuilder`), so
   execution remains subject to the gateway's normal `@PreAuthorize`
   enforcement — gating here controls *visibility*; the gateway/service layer
   remains the enforcement boundary.
4. Return a `ToolProviderResult` with these specification/executor pairs.
   `McpToolProvider.builder().mcpClients(mcpSyncClient).toolFilter((tool, client) -> selectedNames.contains(tool.name())).build()`
   is a viable starting point if it covers steps 3-4 directly; a fully custom
   `ToolProvider` gives more control over step 2's reuse of
   `resolveCandidateTools` and is likely cleaner given the bespoke gating
   requirement.

Sequence this phase **after** the facade-tool permission gating (§§1-8) ships
and is validated in `alpha` — it is additive (expands the candidate pool and
adds an execution path) and independent of the security-critical gating fix,
so it should not block, or be blocked by, that work.

### 11. Role's narrowed responsibilities / `mcp_role` & `mcp_tool_role` disposition

- `McpRoleResolver`, `CurrentUserContext.primaryRole()`, and
  `ToolRegistryRoleMapper` remain — exclusively for system-prompt selection
  (`RolePromptResolver`), RAG-scope selection, agent-cache key namespacing
  (`role + "::" + toolCacheKey(...)`), and chat-memory key namespacing
  (`memoryKey(username, role)`).
- `mcp_role` and `mcp_tool_role`, and the queries that join through them,
  become unused once §5 ships. Do **not** drop these tables in the same
  migration set — keep them present-but-unjoined through the rollout, then
  drop in a follow-up cleanup migration once the new gating is validated in
  `alpha`. This avoids bundling an irreversible schema change with the
  security-critical behavioral change and preserves a fast rollback path.

### 12. Agent-cache prebuilding implications

`MasterAgentRegistry.preloadableRoleIdentifiers()` drives
`SessionAgentManager.prebuildRoleAgents()`, which warms `roleAgentCache` per
role using that role's full tool set. With permission-based gating, the
effective candidate set is now a function of the caller's actual `perm_bits`,
not just their role, so a role-keyed prebuilt entry may not match any
individual caller's `toolCacheKey`. Two paths:

- **(a)** Prebuild using each role's *default* permission set, sourced from
  `pos-security-service`'s `RoleAuthorityServiceImpl` role→permission
  expansion via a new small read-only endpoint (e.g.
  `GET /security-service/v1/permissions/role-defaults/{role}`) — the only
  cross-service change in this spec.
- **(b)** Treat prebuilding as a best-effort warm-cache optimization: it
  benefits the common case (most users' `perm_bits` match their role's
  defaults) and degrades gracefully to an on-demand cache miss + build for any
  user whose `perm_bits` diverge.

Recommend **(b)** for the initial rollout (zero cross-service dependency,
ships faster), with **(a)** as a tracked follow-up if cold-start latency on
first-divergent-request proves to be a problem in practice.

## Security Considerations

- **Cache-key correctness is now a security property, not just a performance
  one.** `roleAgentCache`'s key (`role + "::" + toolCacheKey(selectedTools)`)
  must produce different keys whenever two callers' permission-gated
  `selectedTools` differ. Since `toolCacheKey` is derived from the resolved
  tool list itself (not from role), this should hold automatically — but it
  must be covered by an explicit test, because it is now load-bearing for
  authorization, not merely cache efficiency.
- **The dynamic `ToolProvider` (§10) must derive permission context
  per-invocation from the current request, never from agent-build-time closure
  state.** A cached `PosAssistant` instance is shared across users/requests.
  If the `ToolProvider` captured `permissionCodes` when the agent was first
  built (for User A), and that cached instance were later reused for User B
  (same role, same `toolCacheKey` for the *static* tool set, but different
  `perm_bits` affecting *dynamic* OpenAPI tools), User B could be offered — and
  able to execute — tools scoped to User A's permissions. Reading
  `SecurityContextHolder`/`CurrentUserContext` inside `provideTools()` avoids
  this by construction, but the scenario must be covered by an integration
  test (see Testing Decisions).
- **Tool *visibility* (selection) and tool *execution* (enforcement) are
  deliberately layered, not collapsed.** Permission-gated selection reduces
  what the LLM is offered and reduces accidental 403s, but the downstream
  `@PreAuthorize` checks — via the gateway-relayed bearer token, for both
  facade tools' REST calls and the OpenAPI execution bridge's
  `mcpSyncClient.callTool` — remain the actual authorization boundary. This
  spec must not weaken or bypass that boundary; gating is a UX/precision
  improvement layered on top of unchanged enforcement.
- **`mcp_tool_permission` becomes a security-relevant configuration
  artifact.** A tool with an incorrect (too broad) permission mapping becomes
  selectable by callers who shouldn't see it (mitigated by the enforcement
  layer above, but still an information-disclosure/UX issue). Changes to
  `mcp_tool_permission` — migrations or future admin tooling — should get the
  same review scrutiny as permission/role changes elsewhere in the platform.

## Delivery Plan

1. Add `permissionCodes` to `CurrentUserContext`/`CurrentUserContextResolver`
   and to `ToolSelectionContext`. No behavioral change yet — the field is
   unused by gating until step 5.

2. Migration `V17`: create `mcp_tool_permission`; add `mcp_tool.source`,
   `http_method`, `path_template`, `operation_id` (defaulted so existing rows
   remain valid).

3. Migration `V18`: seed `mcp_tool_permission` for the 16 existing facade
   tools (manual curation per §4), including `AdminFacadeTool`'s mapping for
   the fast-path rework.

4. Update `requiredPermissionsOperationCustomizer` (ideally promoted to
   `pos-security-common`) to emit `x-required-permissions: ["AUTHENTICATED"]`
   for `isAuthenticated()`/no-`@PreAuthorize` operations (§7).

5. Add `findEnabledByPermissionsAndWorkflow`/`findTopKByEmbeddingForPermissions`
   to `ToolMetadataRepository`/Impl; switch `ToolRegistryService.resolveCandidateTools`
   and the admin fast-path to the new permission-gated queries; remove the
   role-gated queries and `findAllRoleNames`.

6. Update `ToolSelectionEngine`/call sites to pass `permissionCodes`; extend
   logging/metrics with permission-gating diagnostics (candidate counts
   before/after permission filtering, fail-closed exclusion counts).

7. Validate in `alpha`: confirm the existing cashier/manager/admin/`ROLE_USER`
   scenarios still pass, plus the new same-role-different-perm-bits scenarios
   (Security Considerations).

8. Extend `OpenApiToolMapper`/`ToolRegistrationServiceImpl` to upsert
   discovered operations into `mcp_tool` + `mcp_tool_permission` (§9); extend
   `ToolEmbeddingInitializer` to cover new rows.

9. Spike, then implement, the dynamic `ToolProvider` execution bridge (§10) for
   `source = 'openapi'` candidates; wire into `SessionAgentManager` and
   `StreamingSessionAgentManager` alongside the existing `.tools(...)`.

10. Cleanup migration: drop `mcp_role`/`mcp_tool_role` and remove
    `ToolRegistryRoleMapper`'s gating-only code paths.

11. (Follow-up, cross-service) Add the `pos-security-service`
    role-default-permissions endpoint and switch agent-cache prebuilding to
    use it (§12a).

## Testing Decisions

- Repository tests for `findEnabledByPermissionsAndWorkflow`/
  `findTopKByEmbeddingForPermissions`: a tool is excluded when the caller's
  permission set does not intersect `mcp_tool_permission`, included when it
  does (single match and OR-multi-match), and a tool with zero
  `mcp_tool_permission` rows is never returned regardless of caller
  (fail-closed).
- `CurrentUserContextResolver` tests: `permissionCodes` correctly extracts bare
  `domain:resource:action` codes from a mixed `authorities` set containing
  `ROLE_*`, `PERM_*`, and bare forms, and always includes `AUTHENTICATED`.
- `ToolRegistryService` tests: same-role, different-`permissionCodes` callers
  produce different candidate sets; the admin fast-path triggers on permission
  match + keyword, not on a `ROLE_ADMIN` string.
- **New security-critical integration test**: two users with identical
  `primaryRole` but different `permissionCodes` (one has an extra permission
  unlocking an additional tool) must (a) receive different
  `selectedTools`/`toolCacheKey` for facade tools, and (b) for the dynamic
  OpenAPI execution bridge, the lower-permission user's
  `ToolProvider.provideTools()` must not return the higher-permission user's
  extra tool, even when both share a cached `PosAssistant` instance for their
  common tool subset.
- `OpenApiToolMapper`/`ToolRegistrationServiceImpl` tests: `x-required-permissions`
  (including the `AUTHENTICATED` sentinel) is correctly read and persisted into
  `mcp_tool_permission` on upsert, and re-registration (`ON CONFLICT`) updates
  rather than duplicates.
- Migration tests (existing Flyway harness): `V17`/`V18` apply cleanly against
  a populated `V16` baseline; a query-based assertion confirms all 16 facade
  tools retain at least one `mcp_tool_permission` row post-migration, to catch
  curation gaps before they cause silent fail-closed exclusions.
- Blocking/streaming parity tests extend naturally: the same permission set +
  workflow state must produce the same selected tool set (facade + OpenAPI)
  across both transports.

## Success Criteria

- Tool candidate selection for any caller is fully explained by
  `permissionCodes ∩ mcp_tool_permission`, intersected with workflow-state
  gating — role no longer appears in the gating query.
- Two callers with the same role but different `perm_bits` provably receive
  different tool candidate sets when their permissions differ, and provably
  identical sets when their permissions are identical, even via a shared
  cached agent.
- Every row in `mcp_tool` has at least one `mcp_tool_permission` row (or is
  intentionally `enabled = false`); zero tools are reachable "by accident" via
  an empty permission mapping.
- The `mcp_tool` candidate pool includes OpenAPI-discovered operations
  (`source = 'openapi'`), and at least one end-to-end scenario demonstrates the
  assistant selecting and successfully executing a `source = 'openapi'` tool
  with no hand-curated facade equivalent.
- Selection logs/metrics report permission-gating diagnostics (candidate
  counts pre/post permission filter, fail-closed exclusion counts) sufficient
  to debug "why wasn't tool X offered" without code changes.

## Out of Scope

- Rewriting `pos-security-service`'s role→permission expansion
  (`RoleAuthorityServiceImpl`) — this spec consumes `perm_bits` as-is.
- Distinguishing AND vs. OR permission composition in `x-required-permissions`
  (§6) — documented limitation, not solved here.
- General RAG/document-taxonomy redesign (carried over from the prior spec's
  scope boundary).
- Per-tool rate limiting or cost-based throttling beyond the existing
  `cost_level`/`avg_latency_ms` scoring inputs.
- An admin UI for managing `mcp_tool_permission` mappings — initial population
  is via migration; ongoing maintenance tooling is a follow-up.

## Open Questions / Follow-ups

- Should `requiredPermissionsOperationCustomizer` (§7) be promoted from 18
  copy-pasted per-service classes into a single `pos-security-common` utility
  as part of this work, or kept as-is with the `AUTHENTICATED` sentinel added
  to all 18 copies independently? Promoting reduces drift risk but touches 18
  modules.
- During a transitional rollout where some services have not yet been updated
  with the `AUTHENTICATED` sentinel, should `pos-mcp-server`'s registration
  pipeline treat "extension entirely absent" as `AUTHENTICATED` or as
  "unmapped/fail-closed"? Recommend treating absent-extension operations as
  `AUTHENTICATED` from day one — the conservative direction for *this*
  sentinel is "include", and the actual enforcement boundary is unaffected —
  but flag this as a temporary over-approximation until all services emit the
  extension consistently.
- The `pos-security-service` role-default-permissions endpoint (§12a) — pursue
  as a separate small spec/PR if needed.

## Further Notes

- This spec is designed to land in two independently-valuable halves: steps
  1-7 (permission-gating for the existing 16 facades — the security-critical
  fix) and steps 8-11 (full OpenAPI tool-surface expansion — the "use the Java
  SDK's complete tool list" idea). If only one half can be prioritized, the
  first should go first: it directly addresses the "weak connection / security
  hole" concern without depending on the higher-uncertainty execution-bridge
  spike.
- The aggregate OpenAPI spec (and therefore `x-required-permissions`) is
  already produced by every service today — no new cross-service
  infrastructure is required to source permission data; the gap is entirely
  within `pos-mcp-server`'s consumption of data that already exists.
- If accepted, recommend a short technical spike (timeboxed, ~1-2 days) for
  §10's execution bridge before committing to the second half's delivery-plan
  ordering, specifically to validate `AiServices.tools(...) + .toolProvider(...)`
  coexistence and the propagation of `CurrentUserContext` into
  `ToolProvider.provideTools()` across `StreamingSessionAgentManager`'s actual
  reactive call chain.
