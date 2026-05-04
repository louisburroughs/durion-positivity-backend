# pos-mcp-server

AI orchestration and MCP (Model Context Protocol) server for the Durion Positivity ETSMS platform. Discovers backend REST APIs from Eureka, registers them as MCP tools, routes natural language requests through LangChain4j agents backed by Ollama, and maintains a pgvector RAG document store for context-augmented queries.

## Responsibilities

- Expose backend service REST endpoints as typed MCP tools with role-based access gating
- Orchestrate multi-step agent conversations via LangChain4j session agents (standard and streaming)
- Embed and retrieve RAG documents using pgvector for context-augmented tool selection
- Persist system prompts, tool metadata, invocation audit logs, and NLTI sessions
- Tune tool priorities adaptively based on invocation success rates (daily cron)
- Manage document ingestion jobs asynchronously (`POST /v1/mcp/documents`)
- Expose NLTI request and audit endpoints

## Key Classes

- `AgentOrchestrationService` — coordinates per-user LangChain4j chat agents with tool narrowing
- `StreamingAgentOrchestrationService` — streaming SSE variant of the agent orchestration path
- `ToolRegistrationService` — loads tool metadata from DB and wires facade tool beans
- `DocumentIngestionService` — asynchronous RAG document ingestion with chunking and embedding
- `IntentParserService` — classifies inbound messages to route direct vs. agent paths
- `SystemPromptService` — manages named system prompts stored in PostgreSQL
- `RolePromptResolver` — resolves the active system prompt for a given role (role → "default" → built-in fallback)
- `StaticRagPreloadService` — preloads configured static classpath documents into the RAG store on startup (alpha profile)

## API Endpoints

- `POST /v1/mcp/chat` — synchronous chat (auth: `mcp:chat:execute`)
- `POST /v1/mcp/chat/stream` — streaming SSE chat (auth: `mcp:chat:stream`)
- `POST /v1/mcp/documents` — ingest a document into the RAG store (auth: `mcp:document:ingest`)
- `GET /v1/mcp/documents/jobs/{jobId}` — check ingestion job status
- `POST /v1/nlt/requests` — submit an NLTI request (auth: `nlti:request:submit`)
- `GET /v1/nlt/audit` — query NLTI audit log (auth: `nlti:audit:read`)
- `GET /v1/prompts` / `PUT /v1/prompts/{id}` — system prompt management
- `GET /v1/llm-apis` / `POST /v1/llm-apis` — LLM API config management

## Configuration

| Property                                        | Default            | Description                               |
| ----------------------------------------------- | ------------------ | ----------------------------------------- |
| `langchain4j.ollama.chat-model.model-name`      | `llama3.1:8b`      | Ollama chat model                         |
| `langchain4j.ollama.embedding-model.model-name` | `nomic-embed-text` | Embedding model for RAG                   |
| `mcp.agent.cache-ttl-minutes`                   | `30`               | Per-user session agent cache TTL          |
| `mcp.rag.chunking.enabled`                      | `true`             | Enable document chunking before embedding |
| `mcp.rag.preload.docs`                          | `[]`               | Static classpath documents to preload     |
| `mcp.tuning.enabled`                            | `true`             | Enable adaptive tool priority tuning      |
| `mcp.tuning.cron`                               | `0 0 2 * * ?`      | Tuning schedule (daily at 02:00)          |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — shared DTOs

## Database

Uses Flyway with PostgreSQL + pgvector extension. Migrations at `src/main/resources/db/migration`. H2 migrations at `src/main/resources/db/h2-migration` for local dev profile.

Key tables added by this module:

- `mcp_rag_preload_record` — immutable audit rows tracking each static document preload attempt (document_id, content_hash, status, loaded_at)

## Startup Behaviour

Three ApplicationRunner beans execute on startup (in addition to tool and event-type registration):

| Runner                             | Profile | Behaviour                                                                                                                                                                                                                                              |
| ---------------------------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `SystemPromptSeedRunner`           | `!test` | Seeds `default`, `ROLE_CASHIER`, `ROLE_MANAGER`, `ROLE_ADMIN`, `ROLE_TECHNICIAN` system prompts if not already present. Best-effort — per-entry failures are logged and skipped.                                                                       |
| `RagPreloadRunner`                 | `alpha` | Calls `StaticRagPreloadService.preloadAll()` to load configured static documents into the RAG store. Hashes each file and skips re-ingestion when hash matches the last successful load. Best-effort — failures are logged but do not prevent startup. |
| `DocumentIngestionJobResumeRunner` | `!test` | Resumes any PENDING/RUNNING ingestion jobs left over from a previous run.                                                                                                                                                                              |

### Role-Aware Prompt Resolution

The system prompt used for each chat session is resolved per-role by `RolePromptResolver`:

1. Look up a system prompt by name exactly matching the user's Spring Security role (e.g. `ROLE_CASHIER`).
2. If not found, look up the prompt named `default`.
3. If not found, use the built-in hardcoded fallback prompt.

Prompts are managed via the `/v1/prompts` CRUD API (requires `mcp:system_prompt:*` permission).

### Static RAG Preload Configuration

Configure static documents to preload in `application.yml`:

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

Each entry specifies a stable `id` (document_id used for supersede semantics) and a `source-path` (classpath resource). Adding a new entry here is all that is required to include an additional static document in the preload registry.

## Development

```bash
./mvnw -pl pos-mcp-server -am spring-boot:run -Dspring-boot.run.profiles=dev
```

Use `docker compose up` from the backend root to start the full local stack including Ollama.
