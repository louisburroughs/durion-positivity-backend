# Gate 3 — OpenAPI Tool Execution Bridge (implementation-ready design)

> **Status:** DESIGN COMPLETE / implementation deferred to live stack. Gate 3 is the security-path
> gate (per-call permission propagation, cached-agent leakage prevention) and requires end-to-end
> verification against a running model backend + gateway. Unlike Gates 0–2C, its code cannot be
> responsibly landed without the live stack — so it is specified here to be implemented and verified
> together when the DB/embedding/gateway tunnel is reachable.

## Goal
Let the agent execute OpenAPI-discovered operations (`mcp_tool.source = 'openapi'`) with no
hand-written facade, while keeping facade tools working and never leaking tools across users.

## Confirmed building blocks
- `dev.langchain4j.service.tool.ToolProvider` + `AiServices.toolProvider(ToolProvider)` exist (langchain4j 1.13.0). `ToolProvider.provideTools(ToolProviderRequest)` is invoked **per request**, returning a `ToolProviderResult` (map of `ToolSpecification` → `ToolExecutor`).
- `mcp_tool.source VARCHAR(20)` (V17, default `facade`, discovered = `openapi`).
- `OperationProxyFactory.handler(serviceId, method, path)` returns
  `BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>>`
  — an **MCP-server-shaped, reactive** handler.
- Permission gating query `findTopKByEmbeddingForPermissions(embedding, limit, permissionCodes, workflowState)` already gates by `mcp_tool_permission ∩ mcp_workflow_state`.

## Gaps to close (the actual work)

### G3.1 — Execution coordinates are not persisted
`mcp_tool` stores name/domain/handler_bean/embedding but **not** the HTTP `method` + `path` +
`serviceId` needed to call a discovered op. Today those exist only in-memory at startup
(`OpenApiToolMapper` builds handlers from the fetched aggregate spec).

**Fix:** persist them. Migration (pg + h2):
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
```
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
- Candidate selection still flows through permission gating — never expose all ~500 ops; no LLM-chosen arbitrary URL (executor only calls persisted method/path for a *selected, gated* op).
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
