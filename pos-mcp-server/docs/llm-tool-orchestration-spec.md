# Onyx + Ollama MCP Integration Specification

## Purpose

This document defines the revised architecture for `pos-mcp-server`.

`pos-mcp-server` will no longer embed LLM orchestration directly. Instead:

- `Onyx` runs as a separate Dockerized agent/orchestration service
- `Ollama` provides the local or self-hosted model runtime used by Onyx
- `pos-mcp-server` remains the MCP tool host for Durion backend capabilities
- Onyx connects to `pos-mcp-server` and uses its tools during agent execution

This change simplifies the MCP server and moves model prompting, tool-use planning, conversation state, and agent behavior into a purpose-built external system.

This specification complements, and does not replace, [tool-registry-implementation.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/tool-registry-implementation.md). That document still defines the preferred approach for narrowing or organizing available tools. Under this revised architecture, that registry may be used inside `pos-mcp-server`, exposed as metadata for Onyx, or deferred until the initial Onyx integration is stable.

## Architecture Decision

### New system boundary

- Onyx is the LLM-facing orchestration layer.
- Ollama is the model-serving layer.
- `pos-mcp-server` is the tool-serving layer.

### Responsibilities

#### Onyx

- connects to Ollama for chat and embedding workloads
- manages prompt construction, memory, retrieval, and tool planning
- decides when to call a tool and how to interpret tool output
- exposes the end-user conversational interface

#### Ollama

- hosts the selected chat model
- optionally hosts the embedding model if Onyx uses embeddings
- remains replaceable behind the Onyx configuration boundary

#### pos-mcp-server

- discovers backend services and exposes approved capabilities as MCP tools
- enforces security, permissions, and observability for tool execution
- owns tool governance and domain-safe wrappers over backend APIs
- optionally owns tool filtering metadata, including role/workflow/intent hints

## Why this direction is good

- It reduces custom orchestration code inside `pos-mcp-server`.
- It lets us adopt a mature agent runtime instead of building one incrementally.
- It keeps the MCP server focused on Durion-specific tool safety and backend integration.
- It makes the model backend replaceable through Onyx without redesigning the MCP server.

## Risks and constraints

- Onyx becomes a new operational dependency.
- Tool security must still be enforced in `pos-mcp-server`, not delegated to prompts.
- We must confirm the exact Onyx integration surface for remote MCP servers and authentication.
- The existing internal LLM skeletons should be treated as draft-only unless we explicitly keep them as fallback infrastructure.

## Target topology

```text
User / UI
   |
   v
 Onyx (Docker)
   | \
   |  \-> Ollama
   |
   \----> pos-mcp-server (MCP tools)
              |
              v
         Durion backend services
```

## Request flow

1. A user sends a request to Onyx.
2. Onyx decides whether the request needs tools.
3. Onyx calls `pos-mcp-server` through its MCP integration.
4. `pos-mcp-server` executes the requested tool against backend services.
5. Tool output is returned to Onyx.
6. Onyx synthesizes the final answer with the Ollama-backed model.

## Scope of pos-mcp-server under this architecture

`pos-mcp-server` should now optimize for:

- stable MCP transport
- safe tool contracts
- durable authorization enforcement
- audit and observability
- backend discovery and proxying
- curated, facade-style tool exposure

`pos-mcp-server` should not optimize for:

- custom conversation memory
- model prompt orchestration
- chat completion vendor abstraction unless required for a fallback mode
- internal tool-calling loops that duplicate Onyx behavior

## Tool design guidance

The recommendation from [tool-registry-implementation.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/tool-registry-implementation.md) still stands:

- prefer `15-25` facade tools, not a huge raw OpenAPI surface
- apply role and workflow gating
- keep deterministic ranking available when tool counts grow
- store tool metadata that supports safe agent selection

Under the Onyx architecture, the registry has three viable roles:

1. Internal execution guardrail

- `pos-mcp-server` uses the registry to decide which tools are visible or callable for a given session or role before exposing them to Onyx

2. Metadata source for Onyx

- `pos-mcp-server` exposes curated tool descriptions, priorities, and domain hints, while Onyx remains the final planner

3. Deferred optimization

- initial rollout exposes a smaller curated tool set directly, and registry scoring is added later if tool volume or routing quality demands it

## Recommended rollout

### Phase 1

- stand up Onyx in Docker
- stand up Ollama and validate model connectivity from Onyx
- connect Onyx to `pos-mcp-server`
- expose a very small set of safe facade tools
- validate end-to-end tool calls, auth, tracing, and timeouts

### Phase 2

- improve tool descriptions and schemas for better agent behavior
- restrict exposed tools by environment and permission
- add domain-specific prompts or workspace guidance in Onyx

### Phase 3

- introduce tool registry metadata or ranking if the exposed tool set becomes too broad
- add richer observability around tool selection outcomes
- decide whether any local fallback orchestration inside `pos-mcp-server` is still needed

## Docker expectations

The minimum deployment shape should include:

- an Onyx container
- an Ollama container or host-level Ollama runtime
- a reachable `pos-mcp-server` endpoint
- environment-driven configuration for model selection and MCP connectivity

Illustrative shape:

```yaml
services:
  onyx:
    image: <onyx-image>
    ports:
      - "3000:3000"
    environment:
      OLLAMA_BASE_URL: http://ollama:11434
      MCP_SERVER_URL: http://pos-mcp-server:8086

  ollama:
    image: ollama/ollama
    ports:
      - "11434:11434"

  pos-mcp-server:
    image: <durion-pos-mcp-server-image>
    ports:
      - "8086:8086"
```

This is illustrative only. The real Onyx configuration contract should be confirmed against the version we deploy.

## Security requirements

- authentication and authorization remain enforced by backend services and `pos-mcp-server`
- Onyx must not be treated as a trusted bypass around tool permissions
- any secrets used for Onyx or Ollama integration must come from environment variables or secret stores
- internal-only tools must remain hidden or inaccessible outside approved runtime paths

This aligns with [ADR-0011](/home/louis-burroughs/IdeaProjects/durion/docs/adr/0011-api-gateway-security-architecture.adr.md) and [ADR-0014](/home/louis-burroughs/IdeaProjects/durion/docs/adr/0014-gateway-internal-service-security.adr.md).

## Observability requirements

- trace each tool invocation from Onyx request to backend execution where possible
- record tool name, latency, success/failure, and correlation id
- distinguish model failures from tool failures
- add runbooks for Onyx unavailable, Ollama unavailable, and MCP connectivity failures

## Configuration direction for pos-mcp-server

The MCP server configuration should now prioritize:

- MCP transport endpoints
- discovery timeouts
- tool registration controls
- tool exposure toggles
- any future registry metadata configuration

The previous direct chat/embedding provider configuration inside `pos-mcp-server` is no longer the primary path. It may be removed later or retained temporarily while the migration to Onyx completes.

## Open questions

1. How exactly does the selected Onyx version connect to remote MCP servers?

- We should verify protocol support, auth model, and whether SSE transport is supported directly.

2. Where should role/workflow-aware tool visibility be enforced?

- My recommendation: in `pos-mcp-server`, not only in Onyx.

3. Do we want to expose the full discovered tool set to Onyx?

- My recommendation: no. Start with a curated facade set.

4. Should the tool registry be phase-1 or phase-2?

- My recommendation: phase-2 unless the first exposed tool set is already too large.

## Recommendation

I think this is a good change.

If the goal is to get an agentic POS assistant working sooner, Onyx + Ollama is a better fit than continuing to build a bespoke orchestration layer inside `pos-mcp-server`. It reduces custom agent infrastructure and lets this module stay focused on something much more Durion-specific and defensible: safe tool exposure.

My strongest recommendation is to keep the first integration small:

- one Onyx instance
- one Ollama model
- a curated set of high-value facade tools
- strict auth and observability from day one

That will give us a much clearer signal on whether we actually need the full registry/ranking machinery immediately, or whether better tool design alone gets us most of the way there.
