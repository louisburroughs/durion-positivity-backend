# pos-mcp-server

AI orchestration and MCP (Model Context Protocol) server for the Durion Positivity ETSMS platform. It discovers
backend REST APIs from the gateway aggregate OpenAPI spec, registers them as MCP tools, routes natural-language
requests through LangChain4j agents backed by Ollama, and maintains a pgvector RAG store for context-augmented
queries. Tool visibility is gated by the caller's **permission codes** (perm_bits), and tool priorities are tuned
adaptively from invocation outcomes.

> This README is the authoritative document for the module. It consolidates the design specs and tuning guides that
> previously lived under `docs/`. Operational runbooks, alert rules, and dashboards remain under
> [`docs/runbooks/`](docs/runbooks/), [`docs/alerts/`](docs/alerts/), and [`docs/dashboards/`](docs/dashboards/).

## Responsibilities

- Expose backend REST endpoints as MCP tools — 17 hand-curated domain **facade tools** plus operations discovered
  from the gateway aggregate OpenAPI spec.
- Gate tool visibility per request by the caller's permission codes intersected with workflow state (see
  [Tool Selection](#tool-selection)).
- Orchestrate multi-step agent conversations via LangChain4j session agents (synchronous and streaming SSE).
- Embed and retrieve RAG documents using pgvector for context-augmented tool selection and answers.
- Persist system prompts, tool metadata, invocation audit logs, and NLTI sessions/requests/intents.
- Tune `mcp_tool.priority` adaptively from invocation success rate and latency (daily cron).
- Run asynchronous, resumable RAG document-ingestion jobs.
- Expose NLTI request submission and audit query endpoints.

## Architecture

```
Frontend / MCP clients
   │  (JWT → gateway → X-Authorities perm_bits)
   ▼
pos-mcp-server (Spring Boot 4.0.x, Java 25)
   ├─ SessionAgentManager / StreamingSessionAgentManager
   │     per-user agent cache (Caffeine, TTL) keyed by role::toolCacheKey
   │     ├─ permission-gated candidate tool set (ToolRegistryService)
   │     ├─ Exa web-search tool (always included)
   │     ├─ Tier-2 RAG ContentRetriever (shared pgvector store)
   │     └─ per-session ChatMemory (MessageWindowChatMemory + semantic store)
   ├─ LangChain4j runtime — OllamaChatModel / OllamaStreamingChatModel,
   │     OllamaEmbeddingModel, AiServices tool-calling loop
   ├─ Tool discovery (internal/discovery/) — fetch gateway aggregate OpenAPI,
   │     map operations → mcp_tool rows, build HTTP proxies
   ├─ Audit + adaptive tuning — every selection/execution logged; daily cron
   │     recomputes priority
   └─ Observability — Micrometer / OpenTelemetry, NLTI audit ledger
        │
        ├─ Ollama (chat + embedding model runtime)
        ├─ PostgreSQL + pgvector (registry, RAG, audit, chat memory)
        └─ Exa (external web-search SaaS)
```

The previous external orchestration platform was replaced by in-process LangChain4j: prompt construction
(`AiServices`), tool-use planning (tool-calling protocol), memory (`MessageWindowChatMemory`), retrieval
(`EmbeddingStoreContentRetriever` + pgvector), and model abstraction (`OllamaChatModel`) are all local.

## API Endpoints

| Method & path                          | Permission              | Purpose                                |
| -------------------------------------- | ----------------------- | -------------------------------------- |
| `POST /v1/mcp/chat`                    | `mcp:chat:execute`      | Synchronous chat                       |
| `POST /v1/mcp/chat/stream`             | `mcp:chat:stream`       | Streaming SSE chat                     |
| `POST /v1/mcp/documents`               | `mcp:document:ingest`   | Ingest a document into the RAG store   |
| `GET  /v1/mcp/documents/jobs/{jobId}`  | `mcp:document:ingest`   | Check ingestion job status             |
| `POST /v1/nlt/requests`                | `nlti:request:submit`   | Submit an NLTI request                 |
| `GET  /v1/nlt/audit`                   | `nlti:audit:read`       | Query the NLTI audit log               |
| `GET/PUT/DELETE /v1/prompts/{id}`      | `mcp:system_prompt:*`   | System prompt CRUD                     |
| `GET/POST/PUT/DELETE /v1/llm-apis`     | `mcp:llm_api:*`         | LLM API config CRUD                    |

Permission constants are defined in `McpPermissions`. Errors use the standard `ApiError` envelope.

## Tool Selection

Tool visibility is **permission-gated**, not role-gated. On each chat request `ToolRegistryService.resolveCandidateTools()`
narrows the active tool set:

1. `ToolMetadataRepository.findTopKByEmbeddingForPermissions()` runs a pgvector ANN query (`<=>` cosine distance)
   that joins `mcp_tool`, `mcp_tool_permission`, and `mcp_workflow_state`. Only tools whose required permission codes
   intersect the caller's `permissionCodes`, and that are valid for the current workflow state, enter the scoring
   window. Gating happens **inside** the query, before the top-K cut.
2. `ToolScorer` ranks candidates by a weighted blend of semantic similarity and normalized priority
   (`Math.clamp(priority, 0.0, 1.0)`).
3. If no embeddings are stored yet, a deterministic fallback returns gated tools sorted by `priority DESC, name ASC`.
4. Selection is capped at `mcp.agent.candidate-tool-limit` (default 8). The Exa web-search tool is always included.
5. The resolved agent is cached keyed by `role::toolCacheKey` (sorted tool names joined with `+`) and expires after
   `mcp.agent.cache-ttl-minutes` (default 30) so DB priority/prompt edits take effect without restart.

**Fail-closed:** a tool with zero `mcp_tool_permission` rows is never selected for any caller. The `AUTHENTICATED`
sentinel marks operations available to any authenticated caller.

**Permission-code extraction:** `CurrentUserContextResolver` derives bare `domain:resource:action` codes from the
`Authentication` authorities (mixed `ROLE_*`, `PERM_*`, and bare forms) and always includes `AUTHENTICATED`.

> **Known limitation — workflow state:** managers currently always evaluate with `WORKFLOW_IDLE`. Deriving workflow
> state from session context to activate non-IDLE tool sets (`CREATING_PO`, `PROCESSING_RETURN`, …) is not yet
> implemented — tracked as a backlog item.

### Facade tools

17 curated facade tools live in `internal/orchestration/tools/`: Accounting, Admin, Catalog, Customer, Events,
Hr, Inventory, Invoice, Location, Order, Pricing, Reporting, ShopManager, Tax, Vehicle, Workorder, and the always-on
Exa web search. Each maps to backend endpoints via a `@LoadBalanced` RestClient. Permission mappings for these tools
are seeded by migration `V18`.

### OpenAPI-discovered tools

`ToolBootstrapRunner` calls `ToolRegistrationService.registerDiscoveredTools()` on startup. `OpenApiDocumentFetcher`
pulls the gateway aggregate spec (`pos-api-gateway`, configurable via `MCP_AGGREGATE_SPEC_URL`), `OpenApiToolMapper`
maps operations to `mcp_tool` rows, and `OperationProxyFactory` builds the HTTP proxy used to execute a call. These
expand the candidate pool beyond the 17 facades. (Wiring discovered operations as directly agent-callable tools via a
LangChain4j `ToolProvider` is a backlog item — see [Backlog](#backlog--missing-features).)

## Audit & Adaptive Tuning

Every tool decision is logged (selected tool, semantic rank, final score, `selected`, `success`, `fallback_invoked`,
latency). A daily cron (`mcp.tuning.cron`, default `0 0 2 * * ?`) recomputes per-tool performance and adjusts
`mcp_tool.priority`:

```
performance_score = (success_rate * 0.6) + ((1 - normalized_latency) * 0.3) + ...
```

Tuning is enabled by default with a runtime kill switch (`mcp.tuning.enabled`). Owning classes: `ToolAuditService`,
`ToolPriorityTuningService`.

## RAG Retrieval Pipeline (Tier 2)

Both session managers use a Tier-2 retrieval chain:

1. Baseline semantic retriever (`maxResults=10`, `minScore=0.6`).
2. Query-expanded retriever (`maxResults=20`, `minScore=0.55`) using deterministic paraphrases.
3. Hybrid merge + de-duplication across both retrievers.
4. Final lexical-aware re-ranking to the top-5 contexts before prompt injection.

Retrieval is role-aware (`RoleAwareMetadataFilter`, `ScopedContentRetrieverFactory`) and chat memory is persisted via
`SemanticChatMemoryStore` with session summarization (`SessionSummary`).

## Configuration

| Property                                        | Env / Default                 | Description                                    |
| ----------------------------------------------- | ----------------------------- | ---------------------------------------------- |
| `langchain4j.ollama.chat-model.model-name`      | `OLLAMA_CHAT_MODEL` `qwen3.5:cloud` | Deliberate default executor model (single default; tier routing may override per request) |
| `langchain4j.ollama.embedding-model.model-name` | `nomic-embed-text`            | Embedding model for RAG                        |
| `mcp.agent.cache-ttl-minutes`                   | `30`                          | Agent cache TTL (role agents + sessions)       |
| `mcp.agent.candidate-tool-limit`                | `MCP_AGENT_CANDIDATE_TOOL_LIMIT` `8` | Max candidate tools per chat request    |
| `mcp.rag.chunking.enabled`                      | `MCP_RAG_CHUNKING_ENABLED` `true`    | Chunk documents before embedding        |
| `mcp.rag.chunking.max-segment-size`             | `MCP_RAG_MAX_SEGMENT_SIZE`    | Max chunk size                                 |
| `mcp.rag.chunking.max-overlap-size`             | `MCP_RAG_MAX_OVERLAP_SIZE`    | Chunk overlap                                  |
| `mcp.rag.preload.docs`                          | `[]`                          | Static classpath documents to preload          |
| `mcp.tuning.enabled`                            | `MCP_TUNING_ENABLED` `false`  | Adaptive tool priority tuning (disabled until regression harness exists — Gate 0) |
| `mcp.tuning.cron`                               | `0 0 2 * * ?`                 | Tuning schedule (daily 02:00)                  |
| `mcp.model.fallback.enabled`                    | `MCP_MODEL_FALLBACK_ENABLED`  | Primary → secondary model fallback             |
| `mcp.discovery.aggregate-spec-url`              | `MCP_AGGREGATE_SPEC_URL`      | Gateway aggregate OpenAPI URL                  |
| Exa web search                                  | `EXA_API_KEY`                 | External web-search API key                    |
| DB connection                                   | `MCP_DB_HOST/PORT/NAME/USER/PASSWORD` | PostgreSQL + pgvector                  |

### Static RAG preload (`alpha` profile)

```yaml
mcp:
  rag:
    preload:
      docs:
        - id: "accounting.de-bookkeeping"
          source-path: "classpath:rag/de-bookkeeping-rag.md"
        - id: "inventory.inv-cntrl"
          source-path: "classpath:rag/inv-cntrl-rag.md"
```

Each entry has a stable `id` (used for supersede semantics) and a classpath `source-path`. Adding an entry is all
that is needed to include a new static document.

## Startup Behaviour

| Runner                             | Profile | Behaviour                                                                                        |
| ---------------------------------- | ------- | ------------------------------------------------------------------------------------------------ |
| `ToolBootstrapRunner`              | all     | Registers MCP tools from the gateway aggregate OpenAPI spec.                                      |
| `SystemPromptSeedRunner`           | `!test` | Upserts `default` and `ROLE_*` prompts from code (best-effort; per-entry failures skipped).       |
| `SimpleChatRuleSeedRunner`         | `!test` | Seeds the simple-chat rule catalog used for direct (non-agent) routing.                           |
| `RagPreloadRunner`                 | `alpha` | Loads configured static documents; hashes each file and skips re-ingestion when the hash matches. |
| `DocumentIngestionJobResumeRunner` | `!test` | Resumes PENDING/RUNNING ingestion jobs left over from a previous run.                             |

### Role-aware prompt resolution

The session system prompt is resolved by `RolePromptResolver`: (1) look up a prompt named exactly the caller's
Spring Security role (e.g. `ROLE_SERVICE_ADVISOR`); (2) if missing, WARN and fall back to the `default` prompt;
(3) if still missing, WARN and use the built-in hardcoded fallback. Prompts are managed via `/v1/prompts`.

## Data Model

Key tables (Flyway migrations under `src/main/resources/db/migration`, H2 variants under `db/h2-migration`):

- `system_prompt`, `llm_api_config` — prompt and model-config CRUD.
- `nlti_session`, `nlti_request`, `nlti_intent`, `nlti_audit_event` — NLTI session/request/intent tracking + audit.
- `mcp_tool`, `mcp_tool_permission`, `mcp_workflow_state` — tool registry, permission gating (`V17`/`V18`), workflow
  gating. `mcp_tool.source` distinguishes facade vs discovered operations.
- `mcp_role`, `mcp_tool_role` — legacy role gating, retained pending cleanup (see [Backlog](#backlog--missing-features)).
- `mcp_tool_invocation_log` — per-decision audit feeding adaptive tuning.
- `mcp_rag_*` — RAG ingestion jobs, preload tracking, and immutable preload audit records.

## Dependencies

- `pos-security-common` — JWT-based security filter.
- `pos-events` — `@EmitEvent` annotation and event registration.
- `pos-shared-dtos` — shared DTOs (`ApiError`, etc.).

## Development

```bash
# Run locally (dev profile, H2)
./mvnw -pl pos-mcp-server -am spring-boot:run -Dspring-boot.run.profiles=dev

# Full local stack incl. Ollama + Postgres/pgvector
docker compose up
```

## Backlog / Missing Features

Tracked separately as GitHub issues. Open items not yet implemented in code:

- **Workflow-state derivation beyond `IDLE`** — persist workflow state on `NltiSession` and thread it through both
  session managers so non-IDLE tool sets activate.
- **OpenAPI tool execution bridge** — wire `source = 'openapi'` discovered operations as agent-callable tools via a
  LangChain4j `ToolProvider` so the assistant can execute them with no facade equivalent.
- **Legacy role-gating cleanup** — drop `mcp_role` / `mcp_tool_role`, `ToolRegistryRoleMapper`, and the role-gated
  repository queries now superseded by permission gating.
- **`AUTHENTICATED` sentinel everywhere** — promote `requiredPermissionsOperationCustomizer` to `pos-security-common`
  and emit the sentinel from all services so unguarded operations gate correctly.
- **Retrieval-quality regression tests** — hit@5 / MRR harness for tool-selection and RAG recall.
- **Hybrid embedding + BM25 retrieval** and an **admin UI for `mcp_tool_permission`** maintenance.
