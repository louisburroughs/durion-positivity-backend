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
| `GET /v1/mcp/conversations`           | `mcp:chat:execute`    | List conversations, pinned first     |
| `POST /v1/mcp/conversations`          | `mcp:chat:execute`    | Create a new conversation            |
| `GET /v1/mcp/conversations/{id}`      | `mcp:chat:execute`    | Get conversation and messages        |
| `PATCH /v1/mcp/conversations/{id}`    | `mcp:chat:execute`    | Rename or pin conversation           |
| `DELETE /v1/mcp/conversations/{id}`   | `mcp:chat:execute`    | Delete a conversation                |
| `DELETE /v1/mcp/conversations`        | `mcp:chat:execute`    | Clear all conversations              |
| `POST /v1/mcp/conversations/{id}/messages` | `mcp:chat:execute` | Append a message to conversation     |
| `POST /v1/mcp/conversations/{id}/messages/{messageId}/feedback` | `mcp:chat:execute` | Rate an assistant answer (#2075) |
| `DELETE /v1/mcp/conversations/{id}/messages/{messageId}/feedback` | `mcp:chat:execute` | Withdraw a rating (#2075) |
| `GET /v1/mcp/conversations/policy`    | `mcp:chat:execute`    | Get retention policy                 |
| `POST /v1/mcp/documents`              | `mcp:document:ingest` | Ingest a document into the RAG store |
| `GET  /v1/mcp/documents/jobs/{jobId}` | `mcp:document:ingest` | Check ingestion job status           |
| `POST /v1/mcp/transcriptions`         | `mcp:chat:execute`    | Transcribe an audio clip (#2074)     |
| `POST /v1/nlt/requests`               | `nlti:request:submit` | Submit an NLTI request               |
| `GET  /v1/nlt/audit`                  | `nlti:audit:read`     | Query the NLTI audit log             |
| `GET/PUT/DELETE /v1/prompts/{id}`     | `mcp:system_prompt:*` | System prompt CRUD                   |
| `GET/POST/PUT/DELETE /v1/llm-apis`    | `mcp:llm_api:*`       | LLM API config CRUD                  |

**Chat response blocks:** `POST /v1/mcp/chat` carries an optional `blocks` array alongside `response`. Blocks are typed rendering units (markdown, table, code, and forward-compatible schema for chart/image/file/error) segmented server-side from the final markdown answer in source order. Older clients may ignore `blocks` and parse `response` instead; when `blocks` is empty or absent, render `response` as before.

**Conversation persistence:** `POST /v1/mcp/chat` now persists each turn and returns `conversationId` and `messageId`. An absent `conversationId` starts a new persisted conversation; clients must echo the returned id on follow-up turns to continue the same conversation. An unknown or foreign UUID answers 404; a non-UUID string keeps the old memory-only behaviour. Chat memory is rebuilt from stored turns on a cache miss; client-appended turns are never replayed into model context.

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
| `mcp.conversation.retention-days`           | `MCP_CONVERSATION_RETENTION_DAYS` `30`      | Days an unpinned conversation stays before purge; pinned conversations exempt                                                                                                                                                                                                                                                                                               |
| `mcp.conversation.purge-interval`           | `MCP_CONVERSATION_PURGE_INTERVAL` `1h`      | Scheduler interval for purging idle unpinned conversations (hourly per tenant via `TenantIterator`)                                                                                                                                                                                                                                                                     |
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

## Audio transcription (#2074)

`POST /v1/mcp/transcriptions` (`multipart/form-data`) is a server-side speech-to-text fallback for browsers
without the in-browser `SpeechRecognition` API (notably Firefox). Parts:

- `audio` (required) — the recorded clip; content type must be `audio/webm`, `audio/ogg`, or `audio/mp4`
  (`;codecs=` parameters are ignored when matching).
- `language` (optional) — a BCP-47 tag, e.g. `en-US`, sent as a multipart field, **not** a query parameter.
  Defaults to the request's resolved `Accept-Language` locale, then to provider auto-detection.

Limits: 5 MiB and 60 seconds, both inclusive. Duration is provider-reported via `response_format=verbose_json`;
models that don't support `verbose_json` (for example OpenAI's `gpt-4o-transcribe`/`gpt-4o-mini-transcribe`) are
unsupported — use `whisper-1` or a self-hosted equivalent that returns it.

| Status | Meaning                                                                          |
| ------ | --------------------------------------------------------------------------------- |
| 200    | Transcript returned (`text`, `language`, `durationSeconds` — omitted if unreported) |
| 400    | `language` is not a well-formed BCP-47 tag                                        |
| 413    | Clip over 5 MiB, or provider-reported duration over 60 seconds                    |
| 415    | Not multipart, `audio` missing/empty, or an unsupported content type              |
| 422    | Nothing intelligible in the clip, or the provider rejected it as undecodable      |
| 503    | Provider not configured, unreachable, or erroring — never a bare 500. Carries `Retry-After: 30` for transient failures; omitted when the provider is simply not configured, since retrying can't help |

**Retention: transcribe-and-discard.** Audio is held in memory for the request only — never written to disk,
persisted, logged, or included in the `MCP_TRANSCRIPTION_EXECUTE` event. It is forwarded to the configured
provider to produce the transcript; a hosted provider's (e.g. OpenAI's) own retention policy applies to the copy
it received, a self-hosted provider retains nothing.

| Property                          | Env / Default                     | Description                                                       |
| ---------------------------------- | ---------------------------------- | ------------------------------------------------------------------- |
| `mcp.transcription.base-url`      | `MCP_TRANSCRIPTION_BASE_URL` _(blank)_ | Any OpenAI-compatible `/audio/transcriptions` endpoint (OpenAI or self-hosted faster-whisper/speaches). Blank disables transcription — the endpoint answers 503 |
| `mcp.transcription.api-key`       | `MCP_TRANSCRIPTION_API_KEY` _(blank)_  | Blank sends no `Authorization` header                             |
| `mcp.transcription.model`         | `MCP_TRANSCRIPTION_MODEL` `whisper-1`  | Must support `response_format=verbose_json`                       |
| `mcp.transcription.timeout`       | `MCP_TRANSCRIPTION_TIMEOUT` `30s`      | Provider call timeout                                              |
| `mcp.transcription.max-bytes`     | `5MB`                              | Server-enforced clip size cap                                     |
| `mcp.transcription.max-duration-seconds` | `60`                         | Server-enforced clip duration cap                                 |

Operator notes:

- Never set `OPENAI_LOG=debug` in any environment — the `openai-java` SDK would then log request/response
  bodies, i.e. the audio and the transcript.
- Any reverse proxy in front of the gateway must allow request bodies of at least 6 MB (this module's
  `spring.servlet.multipart.max-request-size`), or oversize handling happens there instead of here.
- `server.tomcat.max-swallow-size` is set to `10MB`: Tomcat's 2 MB default resets the connection before the 413
  response body can be written for an oversize clip, so the client would see a network error instead of the
  documented `AUDIO_TOO_LARGE`.

## Per-turn feedback (#2075)

`POST /v1/mcp/conversations/{id}/messages/{messageId}/feedback` and
`DELETE /v1/mcp/conversations/{id}/messages/{messageId}/feedback` (both `mcp:chat:execute`, both emitting
`MCP_MESSAGE_FEEDBACK_SET` / `MCP_MESSAGE_FEEDBACK_CLEAR`) let the caller rate one assistant answer helpful or
not helpful. `messageId` is either the `messageId` `POST /mcp/chat` returned for that turn, or a
`ConversationMessage.id` from `GET /v1/mcp/conversations/{id}`.

**Request body (`POST`).**

| Field     | Required | Values                                                          |
| --------- | -------- | ---------------------------------------------------------------- |
| `rating`  | yes      | `helpful` \| `not_helpful`                                       |
| `reason`  | no       | `incorrect` \| `incomplete` \| `not_relevant` \| `other`; `""` is invalid, not "absent" |
| `comment` | no       | Free text, trimmed; blank after trimming is treated as absent; at most 1000 characters after trimming |

An unrecognized `rating`, a missing `rating`, an unrecognized `reason`, `reason: ""`, or a comment over 1000
characters after trimming all answer 400 `VALIDATION_ERROR` with a `fieldErrors[].field` of `rating`, `reason`
or `comment`.

**Semantics.**

- A repeat `POST` replaces the stored rating **in full** — an omitted `reason` or `comment` clears the
  previously stored value, it does not leave it untouched. Concurrent `POST`s are last-write-wins.
- There is one rating per message (the message is already owner-scoped, so there is no separate per-subject
  key). `DELETE` returns 204 whether or not the message was rated, so it is safe to call unconditionally.
- Only the conversation's owner can rate, and only an **assistant answer produced by a chat turn**
  (`POST /mcp/chat`) — a client-appended assistant message (`POST .../messages`) cannot be rated, and neither
  can a `user`-role message. **Streamed turns are not persisted at all (#2073), so they cannot be rated.**
- Every failure to reach a ratable message — the message doesn't exist, is in another conversation, is not
  owned by the caller, belongs to another tenant, is a `user`-role message, is a client-appended message, or
  was purged — answers 404 `MESSAGE_NOT_FOUND` with the same body in every case. It is **never** 403, so a
  caller cannot use the status code to enumerate other subjects' messages.
- Rating a message does not touch `mcp_conversation`: it does not reorder the history rail and does not reset
  the conversation's retention clock.

**Turn summary (`answer_path`, `answer_source`, `tools_called`, `latency_ms`).** Written once, on the assistant
row of every persisted `CHAT`-origin turn, in every environment (not just alpha) — not exposed by any API,
internal grading data only:

| Column         | Meaning                                                                                          |
| -------------- | -------------------------------------------------------------------------------------------------- |
| `answer_path`  | `AGENT` or `SIMPLE_CHAT`, which orchestration path produced the answer. `NULL` means unreported (a pre-#2075 row, or a non-`CHAT` row) |
| `answer_source`| `CONTENT` \| `RE_RENDERED` \| `LADDER` (agent path) or the raw `ChatResponseText.Source` name (both paths when no ladder bean is wired) |
| `tools_called` | JSON array of tool names in call order, duplicates kept, capped at 64                             |
| `latency_ms`   | Wall time from after the rate-limit check to the reply in hand — the same window as the eval trace's `startedAt → completedAt`; excludes segmentation and persistence |

**Grading join.** Run as the tenant (RLS scopes it automatically) or as the DB owner role for a cross-tenant
sweep. No SQL view is created for this: a view bypasses row-level security unless declared with
`security_invoker`, and it would add a relation `TenancySchemaConformanceIT` would have to reason about — a
plain query has neither problem.

```sql
SELECT m.tenant_id, m.conversation_id, m.id AS message_id, m.created_at AS answered_at,
       q.content AS question,
       m.answer_path, m.answer_source, m.tools_called, m.latency_ms,
       m.feedback_rating, m.feedback_reason, m.feedback_comment, m.feedback_at,
       t.turn_id, t.trace_payload
FROM mcp_message m
LEFT JOIN LATERAL (
    SELECT u.content FROM mcp_message u
    WHERE u.tenant_id = m.tenant_id AND u.conversation_id = m.conversation_id AND u.role = 'user'
      AND (u.created_at, u.id) < (m.created_at, m.id)
    ORDER BY u.created_at DESC, u.id DESC LIMIT 1) q ON true
LEFT JOIN mcp_eval_turn_trace t ON t.tenant_id = m.tenant_id AND t.message_id = m.id
WHERE m.feedback_rating IS NOT NULL
  AND m.role = 'assistant' AND m.origin = 'CHAT'
  AND m.feedback_at >= :since
ORDER BY m.feedback_at DESC
```

This is the same query `tenancy/ConversationPersistenceIT` executes verbatim (`GRADING_QUERY`) — keep the two
in sync.

**Alpha trace caveat.** `t.turn_id` / `t.trace_payload` only populate when the `alpha` profile is running with
`mcp.eval.turn-trace.enabled=true` (the alpha default) — every other environment, and alpha with the trace
disabled, always shows `NULL` there even for a rated turn. The trace itself retains for only
`mcp.eval.turn-trace.retention` (24h on alpha), while the turn summary on `mcp_message` lasts as long as the
message (30-day conversation retention, pinned-exempt). There is deliberately no foreign key from
`mcp_eval_turn_trace.message_id` to `mcp_message.id` — the trace is written before the message row exists, the
message may never be persisted (conversation deleted mid-turn), and the two retentions differ — so the join
always includes `tenant_id` alongside `message_id`, not `message_id` alone.

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

