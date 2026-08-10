# Gate 3 — OpenAPI Tool Execution Bridge (implementation-ready design)

> **Status:** IMPLEMENTED + VERIFIED — Gate 3 signed **PASS 2026-08-08** (see
> `implementation_checklist.md` Gate 3 sign-off): core shipped via #779 + the #645 batch PRs,
> cached-agent leakage proven by `CachedAgentOpenApiPermissionLeakageTest` (PR #1199), streaming
> live proof + auth fixes in PRs #1202/#1204 (#1196 CLOSED), alpha zero-404 pass (#645 CLOSED).
> The design below is retained as the implementation record. Original design premise (historical):
> Gate 3 is the security-path gate (per-call permission propagation, cached-agent leakage
> prevention) and required end-to-end verification against a running model backend + gateway.

## Goal

Let the agent execute OpenAPI-discovered operations (`mcp_tool.source = 'openapi'`) with no
hand-written facade, while keeping facade tools working and never leaking tools across users.

## Confirmed building blocks

- `ToolProvider` + `AiServices.toolProvider(...)` exist in the legacy runtime path. `ToolProvider.provideTools(...)` is invoked **per request**, returning a `ToolProviderResult` (map of `ToolSpecification` → `ToolExecutor`).
- `mcp_tool.source VARCHAR(20)` (V17, default `facade`, discovered = `openapi`).
- `OperationProxyFactory.handler(serviceId, method, path)` returns
  `BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>>`
  — an **MCP-server-shaped, reactive** handler.
- Permission gating query `findTopKByEmbeddingForPermissions(embedding, limit, permissionCodes, workflowState)` already gates by `mcp_tool_permission ∩ mcp_workflow_state`.

## Gaps to close (the actual work)

### G3.1 — Discovered ops are not persisted at all (corrected 2026-06-30)

**Verified correction:** discovered OpenAPI ops are **not written to `mcp_tool`**. `ToolRegistrationServiceImpl.registerDiscoveredTools()` only calls `mcpAsyncServer.addTool(spec)` — it registers them in the in-memory MCP server, never the DB. No code `INSERT`s `source='openapi'` rows. (The README's "maps operations → mcp_tool rows" is aspirational.) Execution coordinates exist only inside the per-request handler closure built by `OpenApiToolMapper`.

So G3.1 is **not** a column-population — it must first **add persistence** of discovered ops to `mcp_tool` (row + `source='openapi'` + embedding + coords) so the permission-gated query (`findDiscoveredCandidatesForPermissions`) and `OpenApiToolProvider` have data. That requires the embedding model + DB → **live-dependent, not offline.** The coord columns (V21/V19) are in place and ready.

**Implementation (live):** in `registerDiscoveredTools`, for each discovered op also persist an `mcp_tool` row: name, domain, description, `source='openapi'`, `http_method`, `http_path`, `service_id`, `input_schema`, and an embedding (via the embedding model). Surface method/path/serviceId from `OpenApiToolMapper` (today only `McpSchema.Tool` is returned — add a coord-carrying result). Then `mcp_tool_permission` rows must be seeded/curated for the discovered ops (Gate 7 admin tooling, #785) or they are fail-closed (never selected).

Columns (already shipped — V21 pg / V19 h2):

```sql
ALTER TABLE mcp_tool ADD COLUMN IF NOT EXISTS http_method  VARCHAR(8);
ALTER TABLE mcp_tool ADD COLUMN IF NOT EXISTS http_path    VARCHAR(512);
ALTER TABLE mcp_tool ADD COLUMN IF NOT EXISTS service_id   VARCHAR(128);
ALTER TABLE mcp_tool ADD COLUMN IF NOT EXISTS input_schema TEXT;   -- JSON Schema for args
```

`ToolRegistrationService.registerDiscoveredTools()` already has these at registration — populate the
columns there. Add `httpMethod/httpPath/serviceId/inputSchema` to `ToolMetadata` (+ `mapRow` + the
two SELECTs). Facade rows leave them null.

### G3.2 — Bridge MCP handler → LangChain4j ToolExecutor

`ToolExecutor.execute(ToolExecutionRequest, memoryId)` is **synchronous, returns String**.
`OperationProxyFactory` returns a reactive `Mono<CallToolResult>`. Bridge:

```java
class OpenApiOperationExecutor implements ToolExecutor {
  // built from persisted method/path/serviceId
  String execute(ToolExecutionRequest req, Object memoryId) {
    // re-check permission for THIS op against the current caller (see G3.3) — throw if not allowed
    CallToolRequest call = toCallToolRequest(req.arguments()); // arguments() is JSON
    CallToolResult result = proxyFactory.handler(serviceId, method, path)
        .apply(null /* no MCP exchange */, call)
        .block(timeout);                                       // bridge reactive→sync
    return renderText(result);                                 // controlled error text on failure
  }
}
```

`ToolSpecification` is built from the persisted `input_schema` (JSON Schema → `JsonObjectSchema`).

### G3.3 — Per-call permission propagation + leakage prevention (SECURITY-CRITICAL)

Agents are cached by `role::toolCacheKey`. The `ToolProvider` MUST read the **current caller's**
permission codes at `provideTools`/execute time, never capture them at agent-build time.

**Propagation is path-specific (this is why it needs live verification):**

- **Blocking** (`SessionAgentManager.chat`, on the calling thread): a `ThreadLocal<CurrentUserContext>`
  holder, set before `agent.chat(...)` and cleared in `finally`.
- **Streaming** (`StreamingSessionAgentManager.streamChat`, Reactor): a `ThreadLocal` is **unsafe**
  across reactor threads. Use **Reactor Context** (`contextWrite`) + read it where the tool executes,
  or hop the context onto the executing scheduler. This divergence must be tested, not assumed.

`OpenApiToolProvider.provideTools`:

1. read current `permissionCodes` + `workflowState` from the request-scoped holder/context,
2. query discovered (`source='openapi'`) candidates gated by `permissionCodes ∩ mcp_tool_permission ∩ workflowState` (new `findDiscoveredCandidatesForPermissions`),
3. build `ToolSpecification` + `OpenApiOperationExecutor` for each,
4. the executor re-checks the op's permission at call time (defense in depth — a cached agent must not execute a tool the current caller lacks).

### G3.4 — Facade coexistence + telemetry

- Facade tools stay wired via `.tools(...)`; discovered ops via `.toolProvider(...)`. Both present.
- Telemetry: tag each invoked tool with `source` (`facade`|`openapi`) in `NltiRequestTelemetry.Tools.invoked` (add a `source` field) once the per-request telemetry pipeline lands.

## Drift guards (Gate 3 exit + locks)

- Candidate selection still flows through permission gating — never expose all ~500 ops; no LLM-chosen arbitrary URL (executor only calls persisted method/path for a _selected, gated_ op).
- Arguments schema-validated before the proxy call (from `input_schema`).
- Proxy failure → controlled error string, never a hallucinated success.
- Lower-permission caller cannot invoke a higher-permission op even via a shared cached agent (G3.3 re-check).

## Verification (must run live — see runbook §B.6)

1. End-to-end: a `source='openapi'` op with no facade is callable and returns a real gateway result.
2. Negative: a caller lacking the op's permission never receives it in `provideTools` **and** cannot execute it (executor re-check).
3. Blocking and streaming both work and both enforce the negative case (separate tests — different propagation).
4. Facade tools unaffected (regression).

## Implementation order (when stack is up)

G3.1 (persist coords) → G3.2 (executor bridge) → G3.3 (provider + propagation, both paths) →
wire `.toolProvider(...)` into both managers → G3.4 (telemetry) → live tests §B.6.

## Live verification record — 2026-08-08 (alpha, image `sha-ec16593`, includes PR #1199)

Closes out the two Gate 3 residues tracked by #645 and #1196.

### #645 — zero-404 routing check (PASS, issue closed)

`scripts/gateway_route_check.py` with `ENV_FILE=/opt/durion/alpha/.env`,
`GATEWAY_URL=http://localhost:8080`, and a real `admin.alpha` JWT minted from
pos-security-service via the gateway login endpoint:

- **67** param-free GET `source='openapi'` ops checked through the gateway:
  34× `200`, 19× `400`, 13× `403` — **zero routing 404s**. 275 write ops and
  130 parameterised ops skipped by design.
- The single apparent 404 (`workorder_getapplicableconfiguration`,
  `GET /workorder/v1/workexec/approvalConfigurations/applicable`) is that endpoint's
  documented business 404 (`ResponseEntity.notFound()` when no applicable config exists):
  empty-body 404 vs. the JSON-body signature of a genuinely unmatched route, and the same
  controller's list endpoint returns 200. Script false positive, not a routing miss.
- Raw results: `pos-mcp-server/target/eval/gateway-route-check.json` on the alpha checkout.

### #1196 item 1 — streaming SSE openapi proof

Live streaming session (`POST /mcp-server/v1/mcp/chat/stream` via the gateway, real
`admin.alpha` JWT) proved the selection side of the streaming bridge:

- `OpenApiToolProvider` resolved and attached discovered tools per request on the streaming
  path — `MCP openapi tool provider role=ROLE_ADMIN permissionCount=336 discoveredTools=8`
  (DEBUG, 17:57:14Z) — i.e. the request-scoped publish → resolve → clear cycle works live
  with real caller context, and the write-gate signal was recorded from the resolved set.
- Target op `event-receiver_getactiveeventtypes` (seeded `AUTHENTICATED`) ranks #1 in the
  exact gating query against the live DB (cosine distance 0.112; next candidate 0.41).
- Fail-closed: unauthenticated stream request → 401 in 5 ms at the gateway.

The execution leg surfaced a streaming-only production defect: the `OLLAMA_API_KEY` bearer
header was attached to the RestClient (blocking) only, so every streaming chat 401'd against
the authenticated cloud backend (`https://ollama.com`) while blocking chat worked.
**Fixed in PR #1202** (merged 2026-08-08): the header is now mirrored onto the WebClient via
`OllamaApi.builder().webClientBuilder(...)`, with `OllamaChatModelConfigurationTest` driving
both real clients against a local HTTP server (streaming case fails without the fix).
Item 1 closed after the fix.

Operational notes for future live streaming runs on alpha (CPU-only host): local models need
`OLLAMA_CONTEXT_LENGTH=16384` (default 4096 truncates the assembled master prompt);
the NLTI router model (`qwen3:4b`) is not hosted on ollama.com, so with the cloud backend the
router fails fast and requests default to T2-complex; a secondary defect was observed where an
SSE stream that produces zero data events surfaces as a misleading 401 from
`ApiErrorAuthenticationEntryPoint` on the async completion dispatch (token still valid,
telemetry `SUCCESS`).

### #1196 item 2 — cached-agent permission leakage

`CachedAgentOpenApiPermissionLeakageTest` (shipped in PR #1199) turns the design claim into an
executable proof through the real production seam: user A (holds the gated permission) warms
the cached role agent; user B (lacks it) reuses the same cached instance (single
`roleAgentCache` entry asserted). B's prompt options carry zero openapi callbacks (not offered
⇒ nothing Spring AI could execute), a direct provider resolution under B's context returns
empty, both the gating query and the selection engine receive each request's own permission
codes, the provider fails closed with an unwired or unpublished context, and the thread-local
caller context is cleared after each request.
