# pos-mcp-server

AI orchestration and MCP (Model Context Protocol) server for the Durion Positivity ETSMS platform. It discovers
backend REST APIs from the gateway aggregate OpenAPI spec, registers them as MCP tools, routes natural-language
requests through Spring AI assistants backed by Ollama-compatible chat/streaming models, and maintains a pgvector RAG store for context-augmented
queries. Tool visibility is gated by the caller's **permission codes** (perm_bits), and tool priorities are tuned
adaptively from invocation outcomes.

This README covers setup, endpoints, configuration and startup behavior. Design and operations documentation lives in
the `durion` repository under [`domains/general/mcp-server/`](https://github.com/louisburroughs/durion/blob/master/domains/general/mcp-server) — start at the
[general domain index](https://github.com/louisburroughs/durion/blob/master/domains/general/index.md):

- [Architecture](https://github.com/louisburroughs/durion/blob/master/domains/general/mcp-server/architecture.md) — tool selection, facade and discovered tools, answer resolution,
  RAG, audit and tuning, data model, multitenancy, backlog.
- [Operations](https://github.com/louisburroughs/durion/blob/master/domains/general/mcp-server/operations) — alert rules, dashboards, runbooks.
- [Archive](https://github.com/louisburroughs/durion/blob/master/domains/general/mcp-server/archive/README.md) — phase-gate plans, designs, checklists and gate-run records.

Edit design documentation there, in the same change set as the code it describes. Keep this README to what an
operator needs to build, configure and run the module.

## Responsibilities

- Expose backend REST endpoints as MCP tools — 18 hand-curated domain **facade tools** plus operations discovered
  from the gateway aggregate OpenAPI spec.
- Gate tool visibility per request by the caller's permission codes intersected with workflow state (see
  [Tool Selection](https://github.com/louisburroughs/durion/blob/master/domains/general/mcp-server/architecture.md#tool-selection)).
- Orchestrate multi-step agent conversations via Spring AI session assistants (synchronous and streaming SSE).
- Embed and retrieve RAG documents using pgvector for context-augmented tool selection and answers.
- Persist system prompts, tool metadata, invocation audit logs, and NLTI sessions/requests/intents.
- Tune tool priorities adaptively from invocation success rate and latency (daily cron): a per-tenant overlay from
  each tenant's own invocation log, and the global `mcp_tool.priority` from all tenants' logs (ADR-0062 plan WS6).
- Run asynchronous, resumable RAG document-ingestion jobs.
- Expose NLTI request submission and audit query endpoints.

## API Endpoints

| Method & path                         | Permission            | Purpose                              |
| ------------------------------------- | --------------------- | ------------------------------------ |
| `POST /v1/mcp/chat`                   | `mcp:chat:execute`    | Synchronous chat                     |
| `POST /v1/mcp/chat/stream`            | `mcp:chat:stream`     | Streaming SSE chat                   |
| `POST /v1/mcp/documents`              | `mcp:document:ingest` | Ingest a document into the RAG store |
| `GET  /v1/mcp/documents/jobs/{jobId}` | `mcp:document:ingest` | Check ingestion job status           |
| `POST /v1/nlt/requests`               | `nlti:request:submit` | Submit an NLTI request               |
| `GET  /v1/nlt/audit`                  | `nlti:audit:read`     | Query the NLTI audit log             |
| `GET/PUT/DELETE /v1/prompts/{id}`     | `mcp:system_prompt:*` | System prompt CRUD                   |
| `GET/POST/PUT/DELETE /v1/llm-apis`    | `mcp:llm_api:*`       | LLM API config CRUD                  |

**Chat response blocks:** `POST /v1/mcp/chat` carries an optional `blocks` array alongside `response`. Blocks are typed rendering units (markdown, table, code, and forward-compatible schema for chart/image/file/error) segmented server-side from the final markdown answer in source order. Older clients may ignore `blocks` and parse `response` instead; when `blocks` is empty or absent, render `response` as before.

Permission constants are defined in `McpPermissions`. Errors use the standard `ApiError` envelope.

## Configuration

| Property                                    | Env / Default                               | Description                                                                                                                                                                                                                                                                                                                                                                |
| ------------------------------------------- | ------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `spring.ai.ollama.chat.options.model`       | `OLLAMA_CHAT_MODEL` `deepseek-v4-flash:0731` | Deliberate default executor model (single default; tier routing may override per request)                                                                                                                                                                                                                                                                                  |
| `spring.ai.ollama.chat.options.num-ctx`     | `OLLAMA_NUM_CTX` `32768`                    | Context window, sent to Ollama as `num_ctx`. Defaulted here for every profile so it is never inherited from the backend (#1683) — Ollama silently drops the front of the context, i.e. the system prompt, once the prompt exceeds the window. A request, not a guarantee: a backend may cap it (see the truncation check in the archived [gate verification runbook](https://github.com/louisburroughs/durion/blob/master/domains/general/mcp-server/archive/gate-verification-runbook.md)). Override per host; don't set it empty |
| `spring.ai.ollama.chat.options.temperature` | `OLLAMA_CHAT_TEMPERATURE` `0.0`             | Executor sampling temperature. 0 by default: the analytics gate is graded at n=1, so sampling only adds run-to-run variance                                                                                                                                                                                                                                                |
| `mcp.model.fallback.secondary-model-name`   | `OLLAMA_FALLBACK_MODEL` `deepseek-v4-pro:0813`| Secondary model used when `mcp.model.fallback.enabled=true` (inherits the primary's temperature and `num_ctx`)                                                                                                                                                                                                                                                             |
| `OLLAMA_CHAT_THINK`                         | _(unset)_                                   | `false`/`true` to force Ollama thinking off/on; unset leaves the model default. Set `false` for reasoning models that would otherwise return the answer in the `thinking` channel (blank `content`)                                                                                                                                                                        |
| `spring.ai.ollama.embedding.options.model`  | `OLLAMA_EMBEDDING_MODEL` `bge-m3`           | Embedding model for RAG (1024-dim since the Gate 5 cutover, #1194)                                                                                                                                                                                                                                                                                                         |
| `mcp.agent.cache-ttl-minutes`               | `30`                                        | Agent cache TTL (role agents + sessions)                                                                                                                                                                                                                                                                                                                                   |
| `mcp.agent.candidate-tool-limit`            | `MCP_AGENT_CANDIDATE_TOOL_LIMIT` `8` (alpha `24`) | Max candidate tools per chat request. Keep it above the facade count (#1840; `McpServerPropertiesDefaultsTest`)                                                                                                                                                                                                                                                  |
| `mcp.agent.discovered-tool-limit`           | _(candidate-tool-limit)_ (alpha `16`)       | Max OpenAPI-discovered operations per chat request (#1840)                                                                                                                                                                                                                                                                                                                 |
| `mcp.rag.chunking.enabled`                  | `MCP_RAG_CHUNKING_ENABLED` `true`           | Chunk documents before embedding                                                                                                                                                                                                                                                                                                                                           |
| `mcp.rag.chunking.max-segment-size`         | `MCP_RAG_MAX_SEGMENT_SIZE`                  | Max chunk size                                                                                                                                                                                                                                                                                                                                                             |
| `mcp.rag.chunking.max-overlap-size`         | `MCP_RAG_MAX_OVERLAP_SIZE`                  | Chunk overlap                                                                                                                                                                                                                                                                                                                                                              |
| `mcp.rag.hybrid.lexical-enabled`            | `MCP_RAG_LEXICAL_ENABLED` `true`            | Include scoped PostgreSQL full-text hits in RRF fusion; set `false` for immediate rollback                                                                                                                                                                                                                                                                                 |
| `mcp.rag.preload.docs`                      | `[]`                                        | Static classpath documents to preload                                                                                                                                                                                                                                                                                                                                      |
| `mcp.tuning.mode`                           | `MCP_TUNING_MODE` `off`                     | Adaptive tool priority tuning: `off`, `shadow` (log and count proposals, write nothing) or `live` (write per-tenant overlays and the global row behind the eval gate). `mcp.tuning.enabled=true` (`MCP_TUNING_ENABLED`, deprecated) still means `live`                                                                                                                       |
| `mcp.tuning.cron`                           | `0 0 2 * * ?`                               | Tuning schedule (daily 02:00)                                                                                                                                                                                                                                                                                                                                              |
| `mcp.model.fallback.enabled`                | `MCP_MODEL_FALLBACK_ENABLED` `false` (alpha `true`) | Primary → secondary model fallback (#1691: on in the alpha profile)                                                                                                                                                                                                                                                                                                                                         |
| `mcp.model.tiering-enabled`                 | `MCP_MODEL_TIERING_ENABLED` `false`         | Gate 4 tier routing. **Dormant** (#1683): with `mcp.model.simple`/`complex` blank both T2 tiers resolve to the same model, so enabling it only pays for a per-turn classification call whose outcome cannot change which model answers                                                                                                                                     |
| `mcp.model.simple`                          | `MCP_MODEL_SIMPLE` _(blank)_                | T2-simple executor. Blank = the default executor model. Setting it to a genuinely smaller pulled model is the precondition for turning tiering back on                                                                                                                                                                                                                     |
| `mcp.server.aggregate-spec-url`             | `MCP_AGGREGATE_SPEC_URL`                    | Gateway aggregate OpenAPI URL                                                                                                                                                                                                                                                                                                                                              |
| `pos.tools.http.connect-timeout`            | `POS_TOOLS_HTTP_CONNECT_TIMEOUT` `2s`       | Connect timeout on `loadBalancedRestClientBuilder` (facade HTTP calls, #1660)                                                                                                                                                                                                                                                                                              |
| `pos.tools.http.read-timeout`               | `POS_TOOLS_HTTP_READ_TIMEOUT` `30s`         | Read timeout on `loadBalancedRestClientBuilder`; a stalled downstream now fails with a named `SocketTimeoutException` instead of holding the chat turn (#1660)                                                                                                                                                                                                             |
| Exa web search                              | `EXA_API_KEY`                               | External web-search API key                                                                                                                                                                                                                                                                                                                                                |
| DB connection                               | `MCP_DB_HOST/PORT/NAME/USER/PASSWORD`       | PostgreSQL + pgvector                                                                                                                                                                                                                                                                                                                                                      |

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

| Runner                             | Profile | Behaviour                                                                                         |
| ---------------------------------- | ------- | ------------------------------------------------------------------------------------------------- |
| `ToolBootstrapRunner`              | all     | Registers MCP tools from the gateway aggregate OpenAPI spec.                                      |
| `SystemPromptSeedRunner`           | `!test` | Upserts `default` and `ROLE_*` prompts from code (best-effort; per-entry failures skipped).       |
| `SimpleChatRuleSeedRunner`         | `!test` | Seeds the simple-chat rule catalog used for direct (non-agent) routing.                           |
| `RagPreloadRunner`                 | `alpha` | Loads configured static documents; hashes each file and skips re-ingestion when the hash matches. |
| `DocumentIngestionJobResumeRunner` | `!test` | Resumes PENDING/RUNNING ingestion jobs left over from a previous run.                             |

### Role-aware prompt resolution

The session system prompt is resolved by `RolePromptResolver`: (1) look up a prompt named exactly the caller's
Spring Security role (e.g. `ROLE_SERVICE_ADVISOR`); (2) if missing, WARN and fall back to the `default` prompt;
(3) if still missing, WARN and use the built-in hardcoded fallback. Prompts are managed via `/v1/prompts`.

**Tool-embedding backfill (#1818).** Rows in `mcp_tool` with no embedding are embedded after
`ApplicationReadyEvent` on a dedicated thread, in batches of `mcp.embedding.backfill-batch-size`
(default 8, `MCP_EMBEDDING_BACKFILL_BATCH_SIZE`) descriptions per model call, with a per-tool fallback
when a batch fails. The batch must fit `OLLAMA_EMBEDDING_TIMEOUT` (30s): alpha's CPU model takes ~1.2s
per description, so 8 is ~10s per call. Readiness never waits for it: on 2026-09-06 a serial,
pre-readiness backfill of 884 tools held `/actuator/health` at 503 for twenty minutes and failed the
alpha deploy. A row is invisible to tool selection until its embedding exists (both candidate queries
filter on `embedding IS NOT NULL`), so a large backlog still means a short window of missing tools —
now measured in batches, not in readiness. Progress is logged per batch; shutdown stops the backfill
at the next batch boundary. Each scheduled re-discovery cycle re-triggers the backfill (#1824), so
rows a later cycle inserts are embedded by that cycle's backfill — or by the next cycle's if a backfill
is already running — rather than on the next restart.

## Upstream model errors and retry (#1749)

Blocking chat calls to the Ollama backend retry fast transient failures (HTTP 5xx, refused
connections, resets) within a bounded budget: `mcp.model.retry.max-retries` further attempts after
the first (default 2), exponential back-off from `initial-delay` (1s) with `multiplier` (2) capped at
`max-delay` (5s) — three requests and 3s of waiting, after which the turn fails and the caller sees
the error. The budget bounds attempts, not wall time; a read timeout is not retried, because each
attempt could cost the full `OLLAMA_CHAT_TIMEOUT`. Streaming does not retry at all (Spring AI 2.0
consults the template only on the blocking path). Spring AI's default would have retried ten times
(eleven requests) with back-off up to three minutes; on 2026-09-05 that turned one `ollama.com` 500
into a turn that outlived the client's 180s timeout and read as a hang. Declaring this template
supersedes `spring.ai.retry.*` for the module. The fallback model (`mcp.model.fallback.*`) shares it.
Environment: `MCP_MODEL_RETRY_MAX_RETRIES`, `MCP_MODEL_RETRY_INITIAL_DELAY`, `MCP_MODEL_RETRY_MULTIPLIER`,
`MCP_MODEL_RETRY_MAX_DELAY`.
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

