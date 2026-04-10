# LLM Tool Orchestration Specification

## Purpose

This document defines how `pos-mcp-server` should integrate an LLM runtime with the existing MCP server, discovered backend tools, and the planned registry-based candidate selection flow.

The target implementation keeps `pos-mcp-server` as the orchestration boundary:

- MCP discovery and proxy execution remain in `pos-mcp-server`
- the tool registry narrows tool choices before model planning
- the LLM decides whether to answer directly or request tool execution
- tool execution remains observable, auditable, and permission-gated

This specification complements, and does not replace, [tool-registry-implementation.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/tool-registry-implementation.md). That document defines the registry scoring and candidate-selection algorithm. This document defines how that registry participates in end-to-end request orchestration with an LLM provider such as Ollama.

## Decisions

- `pos-mcp-server` will support an embedded-agent architecture.
- Ollama is the default local development provider.
- `mcp.llm.apis` remains the provider catalog and may contain OpenAI, Azure OpenAI, and Ollama entries together.
- LangChain is not required for v1.
- The tool registry is the pre-filter for tool choice; the LLM should only see the reduced candidate set.
- Deterministic fallback remains mandatory when the embedding provider or chat provider is unavailable.

## Architecture

### Runtime roles

- `ToolRegistrationService` discovers backend OpenAPI operations and registers MCP tools.
- `ToolRegistryResolver` narrows candidate tools using role, workflow state, intent, and embeddings.
- `ChatModelClient` translates orchestration requests into provider-specific chat API calls.
- `EmbeddingService` translates free text into vectors for semantic ranking.
- `LlmToolOrchestrator` owns the tool-call loop and final response assembly.
- `McpSyncClient` executes MCP tools selected by the orchestrator.

### Request flow

1. The caller submits a natural-language request.
2. Session, user identity, and workflow state are resolved.
3. `ToolRegistryResolver` computes the candidate set.
4. `LlmToolOrchestrator` sends the prompt, system prompt, and candidate tool definitions to `ChatModelClient`.
5. The model returns either:
   - a final natural-language answer, or
   - one or more requested tool calls.
6. Tool calls are executed through `McpSyncClient`.
7. Tool outputs are appended to the conversation and the model is called again.
8. The loop stops when a final answer is produced or `maxToolIterations` is reached.
9. Audit, metrics, and traces are emitted for candidate selection, provider calls, tool calls, and fallback paths.

## Integration with Tool Registry

The registry algorithm described in [tool-registry-implementation.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/tool-registry-implementation.md) is the normative baseline for candidate selection:

- role + workflow gating happens before semantic ranking
- embeddings drive semantic top-K retrieval
- deterministic scoring combines semantic rank, priority, latency, and cost
- the orchestrator should receive only the highest-confidence candidates, normally `3-5`

Additional orchestration requirements:

- the registry must expose a deterministic non-embedding fallback path
- the orchestrator must never forward tools the user is not authorized to use
- if registry resolution fails, the request must degrade to a safe answer or a no-tool response path

## Class Skeleton Plan

### Configuration

- `LlmRuntimeProperties`
  - provider selection
  - default chat API id
  - embedding API id
  - provider timeouts
  - max tool iterations
  - system prompt
- `ToolRegistryProperties`
  - enablement flag
  - semantic top-K
  - returned candidate count
  - default workflow state
  - deterministic fallback toggle

### Provider abstraction

- `ChatModelClient`
  - provider-agnostic contract for chat completion and tool-call planning
- `EmbeddingService`
  - provider-agnostic contract for embeddings
- `OllamaChatModelClient`
  - translates internal chat request model to Ollama `/api/chat`
- `OllamaEmbeddingService`
  - translates text input to Ollama `/api/embeddings`
- `NoopChatModelClient`
  - safe fallback bean when no provider is enabled
- `NoopEmbeddingService`
  - safe fallback bean for deterministic registry mode

### Registry orchestration

- `ToolSelectionContext`
  - prompt, role, workflow state, intent, session id, correlation id
- `ToolRegistryCandidate`
  - tool metadata required by the orchestrator
- `ToolRegistryResolver`
  - contract for resolving candidate tools
- `DefaultToolRegistryResolver`
  - initial resolver implementation that will later incorporate repository and pgvector queries

### Orchestration

- `LlmToolOrchestrator`
  - builds provider request payloads
  - enforces bounded tool-call loop
  - maps tool results back into model messages
  - applies fallback rules

## Configuration Contract

Recommended `application.yml` structure:

```yaml
mcp:
  llm:
    runtime:
      provider: ollama
      default-api-id: ollama-chat
      embedding-api-id: ollama-embedding
      max-tool-iterations: 4
      degrade-on-provider-failure: true
      connect-timeout: 2s
      read-timeout: 30s
      system-prompt: |
        You are the Durion POS MCP orchestrator.
        Use only the provided tools.
        Prefer a direct answer when no tool is required.
    apis:
      - id: ollama-chat
        model: llama3.1:8b
        base-url: http://localhost:11434/api/chat
      - id: ollama-embedding
        model: nomic-embed-text
        base-url: http://localhost:11434/api/embeddings
  registry:
    enabled: true
    semantic-top-k: 10
    returned-candidates: 5
    deterministic-fallback-enabled: true
    default-workflow-state: IDLE
```

## Ollama API Expectations

### Chat

- endpoint: `/api/chat`
- request must include `model`
- request should include a message list converted from orchestrator conversation state
- tool definitions should be included only after registry narrowing

### Embeddings

- endpoint: `/api/embeddings`
- request must include the embedding model and input text
- failures must not block deterministic fallback

## Implementation Sequence

1. Introduce configuration properties and provider interfaces.
2. Add Ollama chat and embedding client skeletons.
3. Add registry resolver and orchestration service skeletons.
4. Wire YAML defaults for local Ollama development.
5. Implement provider-specific request/response mapping.
6. Implement the bounded tool-call loop.
7. Connect registry candidates into orchestration.
8. Add audit, metrics, and failure-mode tests.

## Operational Guardrails

- default to fail-safe behavior when provider calls fail
- keep tool iterations bounded
- capture provider latency and failure counters
- do not expose full discovered tool inventory to the model when registry filtering is enabled
- keep secrets in environment variables or secret stores only

## Out of Scope for This Draft

- full pgvector repository implementation
- full tool audit feedback loop
- provider streaming responses
- long-term conversation memory
- LangChain or Spring AI adoption

Those can be added later if the direct provider integration becomes cumbersome, but they are not prerequisites for the first production-capable implementation.
