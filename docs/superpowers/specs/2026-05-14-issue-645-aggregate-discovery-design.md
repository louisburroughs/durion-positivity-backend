# Issue #645 Aggregate-First Discovery Design

## Problem

Issue #645 is broader than a single implementation pass: it spans aggregate-first discovery, fallback behavior, refresh scheduling, metrics, rollout validation, and operations work. For the first slice, the goal is narrower: replace the current per-service auto-discovery path in `pos-mcp-server` with aggregate-first discovery from the gateway while keeping existing manual facade tools untouched.

This first slice exists to solve the current path mismatch problem that causes 404s in alpha. The gateway aggregate already exposes paths in the form the gateway serves, so it is the right source of truth for auto-discovered MCP tools.

## Scope

### In scope

- Fetch a single aggregate OpenAPI document from the gateway
- Replace per-service auto-discovery as the default registration path
- Keep existing manual facade tools unchanged
- Generate auto-discovered tool names using `{domain}_{operationId}`
- Exclude `admin`, `actuator`, and `internal` paths from auto-discovered registration
- Keep startup alive if aggregate discovery fails, with no auto-discovered tools registered

### Out of scope

- Per-service fallback if aggregate fetch fails
- Periodic refresh or dynamic re-registration
- Discovery or registration metrics
- Alpha rollout verification and operational runbooks
- Manual facade tool consolidation or removal

## Architecture

`pos-mcp-server` should keep one discovery pipeline, but switch the document source for auto-discovered tools from “loop over Eureka services and fetch each service spec” to “fetch one aggregate document from the gateway and map tools from it.” This keeps the integration surface small and preserves the existing startup flow.

The component split for the first slice should be:

- `ToolBootstrapRunner` remains the startup trigger for tool registration
- `ToolRegistrationServiceImpl` remains the orchestrator, but now performs aggregate-first registration instead of per-service iteration
- `OpenApiDocumentFetcher` gains an aggregate-fetch path that retrieves and parses the gateway aggregate spec
- `OpenApiToolMapper` maps aggregate operations into MCP tools and derives `{domain}_{operationId}` names from aggregate paths
- Manual facade tools remain outside this change and continue to register and behave as they do now

This avoids introducing a second registration subsystem or a large strategy framework before the aggregate path is proven.

## Data Flow

1. `ToolBootstrapRunner` starts tool discovery at application startup.
2. `ToolRegistrationServiceImpl` requests the aggregate OpenAPI document from the gateway through `OpenApiDocumentFetcher`.
3. `OpenApiDocumentFetcher` fetches the configured aggregate-spec endpoint, parses it once, and returns the resolved OpenAPI model.
4. `OpenApiToolMapper` iterates aggregate paths and operations, filters out excluded paths, derives the tool domain from the first path segment after `/v1/`, and builds MCP tool specifications.
5. `ToolRegistrationServiceImpl` registers the resulting specifications with `McpAsyncServer` and publishes `notifyToolsListChanged()`.

For naming, the mapper should use the aggregate path as the source of domain identity. Example:

- path: `/v1/accounting/invoices`
- operationId: `createInvoice`
- generated tool name before sanitization: `accounting_createInvoice`

The existing name sanitizer can continue normalizing the final MCP tool name.

## Configuration

The first slice should add only the configuration needed for aggregate-first discovery:

- aggregate-spec URL or path under `mcp.server`
- reuse the existing discovery timeout
- keep existing path allowlisting behavior
- add explicit exclusion handling for `admin`, `actuator`, and `internal` paths

This should not introduce full strategy-selection configuration yet. The first slice is a direct cutover of the auto-discovery path, not a permanent dual-mode system.

## Failure Behavior

Aggregate-first discovery should be fail-soft in this first slice.

If the aggregate spec cannot be fetched or parsed:

- the MCP server still starts
- manual facade tools remain available
- no auto-discovered tools are registered
- logs should clearly state that aggregate auto-discovery failed and that registration was skipped

This keeps the discovery enhancement from becoming a hard startup dependency before fallback support exists.

## Testing Strategy

### Aggregate fetch tests

Add focused tests for aggregate fetch success and failure behavior:

- successful fetch and parse of the configured aggregate document
- parse failure handling for malformed aggregate content
- timeout or retrieval failure returning the fail-soft registration path

### Mapper tests

Add tests around aggregate-specific mapping rules:

- derive domain from aggregate paths
- generate `{domain}_{operationId}` tool names
- preserve summary and description for MCP tool metadata
- exclude `admin`, `actuator`, and `internal` paths
- ignore paths outside configured allowlists

### Registration tests

Add orchestration-level tests for:

- aggregate-first registration replacing per-service iteration
- successful registration of tools from one aggregate document
- fail-soft startup behavior when aggregate fetch fails
- no regression to manual tool registration behavior

## Success Criteria

- `ToolRegistrationServiceImpl` uses aggregate-first registration as the default auto-discovery path
- Auto-discovered tool names follow `{domain}_{operationId}`
- Auto-discovered tools are built from gateway-correct aggregate paths rather than per-service spec fetches
- `admin`, `actuator`, and `internal` paths are excluded from auto-discovered registration
- Aggregate fetch or parse failure does not stop server startup, but skips auto-discovered registration with clear logging
- Manual facade tools remain unaffected

## Follow-on Work

After this slice is complete and verified, later work can layer on:

- per-service fallback when aggregate discovery fails
- periodic refresh and re-registration behavior
- discovery and registration metrics
- rollout validation in alpha and production
- operational alerts and runbook coverage
