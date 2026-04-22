# pos-mcp-server

AI orchestration and MCP (Model Context Protocol) server for the Durion POS platform. Discovers backend REST APIs from Eureka, registers them as MCP tools, routes natural language requests through LangChain4j agents backed by Ollama, and maintains a pgvector RAG document store for context-augmented queries.

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
| `mcp.tuning.enabled`                            | `true`             | Enable adaptive tool priority tuning      |
| `mcp.tuning.cron`                               | `0 0 2 * * ?`      | Tuning schedule (daily at 02:00)          |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — shared DTOs

## Database

Uses Flyway with PostgreSQL + pgvector extension. Migrations at `src/main/resources/db/migration`. H2 migrations at `src/main/resources/db/h2-migration` for local dev profile.

## Development

```bash
./mvnw -pl pos-mcp-server -am spring-boot:run -Dspring-boot.run.profiles=dev
```

Use `docker compose up` from the backend root to start the full local stack including Ollama.
