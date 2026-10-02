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
| `mcp.server.excluded-path-fragments`        | `/admin/`, `/actuator/`, `/internal/`       | Substrings that drop a whole path (every method) from tool discovery                                                                                                                                                                                                                                                                                                       |
| `mcp.server.excluded-write-path-patterns`   | see [Tool discovery](#tool-discovery)       | Regexes over the routing-prefixed path whose non-GET operations are never discovered as tools (#2370); GET stays                                                                                                                                                                                                                                                            |
| `pos.tools.http.connect-timeout`            | `POS_TOOLS_HTTP_CONNECT_TIMEOUT` `2s`       | Connect timeout on `loadBalancedRestClientBuilder` (facade HTTP calls, #1660)                                                                                                                                                                                                                                                                                              |
| `pos.tools.http.read-timeout`               | `POS_TOOLS_HTTP_READ_TIMEOUT` `30s`         | Read timeout on `loadBalancedRestClientBuilder`; a stalled downstream now fails with a named `SocketTimeoutException` instead of holding the chat turn (#1660)                                                                                                                                                                                                             |
| Exa web search                              | `EXA_API_KEY`                               | External web-search API key                                                                                                                                                                                                                                                                                                                                                |
| DB connection                               | `MCP_DB_HOST/PORT/NAME/USER/PASSWORD`       | PostgreSQL + pgvector                                                                                                                                                                                                                                                                                                                                                      |

### Tool discovery

`ToolBootstrapRunner` fetches every service's OpenAPI spec through the gateway, prefixes each path with the
service's routing prefix (`/security-service/v1/audit/events`) and registers one `mcp_tool` row per operation,
minus `mcp.server.excluded-path-fragments` (admin, actuator and internal paths, every method). Audit and
platform-event **writes** are additionally never offered as agent tools (#2370): a tool call the user authorises
may *cause* an audit event in the service that performs the action, but the assistant never emits, alters or
deletes evidence itself, and platform event emission and registration are service-to-service. The
`mcp.server.excluded-write-path-patterns` defaults drop every non-GET operation on `pos-security-service`
`/v1/audit/**` (`POST /v1/audit/events`, `PUT`/`DELETE /v1/audit/events/**`, `POST /v1/audit/exports`,
`POST /v1/audit/pricing-snapshots`), `pos-accounting` `/v1/accounting/audit/**` (`POST cancellation`,
`price-override`, `refund`: audit-trail writes), `pos-event-receiver` `/v1/events` (emit) and `/v1/eventTypes`
(register, update, delete), and this module's own `/v1/mcp/audit` or `/v1/nlt/audit` should they gain writes.
`GET` on the same paths stays discoverable (reading the audit log is a legitimate admin question, ADR-0068). The
patterns are anchored on the routing prefix so business paths that merely contain `audit` or `events`
(pos-accounting's event submit, retry and reprocess, for instance) keep their writes; rows registered before the
exclusion are pruned on the next discovery run. The per-service Eureka fallback applies the same exclusion: when
the aggregate yields no tools, or a partial aggregate's failed prefixes are retried service by service, each
service's own spec carries unprefixed paths (`/v1/audit/events`), so they are matched with the service's routing
prefix prepended (its Eureka id, lower-cased, `pos-` stripped: `security-service` or `pos-security-service` →
`/security-service/v1/audit/events`). Neither fallback can put an excluded write on the live tool list.
`DiscoveryAuditWriteExclusionRealSpecsTest` checks all of this, for the aggregate and the fallback mapping,
against the module specs in the reactor checkout.

### Static RAG preload (`alpha` profile)

```yaml
mcp:
  rag:
    preload:
      docs:
        - id: "accounting.de-bookkeeping"
          source-path: "classpath:rag/de-bookkeeping-rag.md"
          rag-scope: "accounting"
          entities: [gl-account, journal-entry, financial-report]
        - id: "inventory.inv-cntrl"
          source-path: "classpath:rag/inv-cntrl-rag.md"
          rag-scope: "inventory"
          entities: [stock-item, stock-transfer, stock-adjustment]
```

Each entry has a stable `id` (used for supersede semantics) and a classpath `source-path`. Adding an entry is all
that is needed to include a new static document, together with the metadata below.

**Document metadata contract.** Every entry of `mcp.rag.preload.docs` declares:

| Key                    | Meaning                                                                                                                              |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `rag-scope`            | The retrieval scope (`accounting`, `inventory`, `shopmanager`, `hr`, `master`, ...).                                                 |
| `required-permissions` | Permission codes that gate the document; omit for none, `AUTHENTICATED` for any signed-in user.                                      |
| `entities`             | ADR-0069: the `scope-graph/entities.yaml` keys the document substantively explains, or `[none]` for a platform-wide document.        |

The `alpha` profile's list replaces the base list wholesale, so **both** `application.yml` and `application-alpha.yml`
carry every entry with the same values, `entities` included. A document header (YAML front matter or the inline
`RAG id:` / `RAG scope:` / `Required permissions:` lines), where present, must agree with its entry on id, scope and
permissions; headers do not carry `entities`. `RagPreloadProfileParityTest` and `RagDocumentHeaderAgreementTest` enforce
both rules.

## Scope graph (ADR-0069)

A generated, in-memory graph of entities, tools, RAG documents, screens and permissions that narrows retrieval and
tool selection before the model runs. It holds platform definitions only, never a tenant's records. **`mode: off` is
the default: nothing is built, read or logged.**

| Property                                   | Env / Default                      | Description                                                                                                             |
| ------------------------------------------ | ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `mcp.scope-graph.mode`                     | `MCP_SCOPE_GRAPH_MODE` `off`       | `off` builds nothing; `shadow` builds the graph and records the resolved scope, no consumer acts on it; `enforce` acts. |
| `mcp.scope-graph.enforce`                  | `MCP_SCOPE_GRAPH_ENFORCE` _(empty)_ | Consumers that act when `mode` is `enforce`: any of `rag`, `tools`, `card`, `lookups`. Empty behaves as `shadow`.       |
| `mcp.scope-graph.max-nodes`                | `60`                               | Cap on the nodes of one expanded scope (two hops from the seed entities).                                               |
| `mcp.scope-graph.added-tool-slots`         | `8`                                | Cap on the tools the scope may add on top of the ranked cuts, facade and discovered together.                           |
| `mcp.scope-graph.card-token-budget`        | `400`                              | Token budget of the scope card appended to the system prompt.                                                           |

Quote a literal mode in YAML (`"off"`): bare `off` is the boolean `false`.

The curated input is `src/main/resources/scope-graph/entities.yaml` (entity lexicon: terms in en/fr/es, identifier
patterns, relations, OpenAPI schema names, facade tools, screens; the field-by-field contract is in its header). The rest
of the graph is derived from the tool catalog, `mcp.rag.preload.docs`, the module OpenAPI specs and the screen registry.
`ScopeGraphRealConfigValidationTest` builds the real inputs under the default and `alpha` profiles and fails on any strict
finding; it reads the `openapi.yaml` files of the sibling modules, so run it from a full reactor checkout.

**Adding an entity**

1. Add it to `entities.yaml`: lower-case hyphenated `key`, `domain` (tool-catalog spelling), at least one singular term in each of en, fr (fr-CA) and es.
2. Attach its DTOs with `schemas` (`domain:SchemaName`, the canonical response first) and tight whole-string `schema_patterns`; list the facade tools that act on it under `facade_tools` (`reads` or `writes`).
3. Add `identifiers` only for formats documented in `rag/glossary-identifiers.md`, and `relates_to` only for relationships a RAG document states.
4. Point the RAG documents that explain it at the new key in **both** preload lists.
5. Run `./mvnw -pl pos-mcp-server -am test`; the real-config test names every unresolved reference.

**Adding a RAG document**

1. Put the file under `src/main/resources/rag/` with a header that matches the entry (or none).
2. Add the entry to **both** `application.yml` and `application-alpha.yml`: `id`, `source-path`, `rag-scope`, `required-permissions`, `entities`.
3. Use entity keys from `entities.yaml`, or `[none]` only for a platform-wide document; list what the document substantively explains, not everything it mentions.
4. A new `rag-scope` spelled differently from a tool domain needs a `domain_scopes` line in `entities.yaml`.
5. Run the module tests: the parity, header-agreement and real-config tests cover the rest.

### Per-turn resolution and shadow recording

With `mode: shadow` or `enforce`, `ToolSelectionEngine.selectRoleTools` resolves a `ScopeSet` for every agent-path turn
(never for simple chat): entities seeded from the message by lexicon terms and identifier patterns, expanded two hops,
capped at `max-nodes`, then filtered to what the caller may see. Seeds carry the entity key and the match kind only, never
the matched text. The scope is published on `RequestScopedUserContext` beside the caller for the duration of the agent
call and cleared with it. Until a consumer is listed in `enforce`, selection, retrieval and the prompt are unchanged.

### Consumers

A consumer acts only when `mode` is `enforce` **and** it is listed in `mcp.scope-graph.enforce`; `mode: enforce` with an
empty list behaves exactly as `shadow`. Each consumer falls back to today's behaviour when the turn's confidence is
below what it acts on, and every such turn is counted under `mcp.scope.fallback{consumer}`.

| Consumer | Acts on      | When enforced                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| -------- | ------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `rag`    | `HIGH`       | Both session managers build the dense, expanded and lexical retrievers over **all** scopes (as for the `master` agent today) and install `ScopeRagFilter` after fusion, before the top-5 cut: keep a chunk whose `document_id` is in the scope or whose `rag_scope` is `master`. On `LOW`/`NONE` the hook re-applies today's eligibility (`rag_scope IN (agent scope, master)`; everything for a `master` agent). Read once at startup: changing it needs a restart. |
| `tools`  | `HIGH`, `LOW` | At most `added-tool-slots` tools per turn are **added** on top of the ranked cuts, never displacing a ranked tool: the scope's facades first (`ToolSelectionEngine`, intersected with the caller's gated set from the same SQL that gates the ranking), then discovered operations with the slots left (`OpenApiToolProvider`, admitted by `findDiscoveredByNamesForPermissions`, the ANN gate's predicates by name). Ordered by hop, reads before writes, name. Nothing is added on the fail-closed paths, on the admin fast path or at warm-up. |
| `lookups` | any seed    | ADR-0069 §6 row 3 / ADR-0068 spec §2.6: the lexicon lookups. When the turn has an entity seed, the tag-added inventory / order facades are replaced by the lexicon `facade_tools` of its seed entities (the scope's hop-1 facades, gated as before, outside the `added-tool-slots` cap); with no entity seed (none named or tagged, a domain-only scope) the keyword tags decide as today, and only a missing scope counts as a `lookups` fallback. For a turn whose acting `intent` is `ACTION`, the heuristic workflow state is the lexicon `workflow_state` of a named or tagged entity (`purchase-order: CREATING_PO`, `asn: RECEIVING_ASN`) before the phrase match. See Question tagging below. |
| `card`   | `HIGH`       | A plain-text scope card (`ScopeCardRenderer`, budget `card-token-budget`) is appended per request as the final system-prompt layer `SCOPE_CARD`, from graph definitions only: qualifying entities, relations, lifecycle states, permitted actions with the codes the caller holds, screens with their URL. Never message text, never a node the caller lacks permission for. Cached agents never bake it in.                                                        |

`addedTools` and `ragFilterApplied` on the eval trace, and `scopeAddedToolCount` / `scopeRagFilterApplied` on the
telemetry event, record what the consumers did on a turn; `SCOPE_CARD` appears in the telemetry `promptLayers` only
when a card was rendered.

**Promotion (ADR-0069 §9).** Consumers are promoted one at a time — RAG filter first, then tool slots, then the card —
and only after a recorded gate run against the same run in `shadow` shows no regression in RAG hit@5, MRR and recall@k,
no increase in forbidden-document violations, and a tool-selection hit rate at least equal. The promotion and its
evidence are recorded in the ADR's changelog.

**Recording.** The alpha eval turn trace gains a nullable `scope` (`mode`, `enforced`, `graphHash`, `graphBuiltAt`,
`confidence`, `seeds[{entity, matchKind}]`, entity/tool/document/screen counts, `addedTools`, `ragFilterApplied`, and at
completion `calledToolsInScope/calledTools` and `retrievedDocsInScope/retrievedDocs`). Older payloads read `scope: null`.
The counts alone cannot say whether the filter would have kept the *right* document, so the scope also records
identities, all of them platform definitions (ADR-0069 §8: document ids, scope names, tool names; never message text):

| Field                        | Content                                                                                                                                                                                              |
| ---------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `retrievedDocuments`         | The final top-K handed to the model, in rank order, one `{documentId, ragScope}` per distinct document (chunks of one document collapse to the first, so one retrieval yields at most K). Null when retrieval was not observed. |
| `scopeDocumentIds`           | The scope's `document_id`s in scope order, at most 64; `scopeDocumentIdsTruncated` says when the list was cut.                                                                                   |
| `scopeToolNames`             | The `mcp_tool.name` of every scope tool, facade and discovered, at most 64; `scopeToolNamesTruncated` likewise.                                                                                   |
| `addedToolNames`             | The names behind `addedTools`.                                                                                                                                                                       |

All four are null in a payload written before they existed (`EvalTurnTraceJsonCompatibilityTest`). `ScopeRetrievalObserver`
records `retrievedDocuments` after the top-K cut and returns the retriever's list untouched (same instance, same order).

**Scope-graph gate report (ADR-0069 §9, offline).** `scripts/scope_graph_gate_report.py` computes the `rag` promotion gate
the way §9 states it: a recorded run with the `rag` consumer in `enforce`, against the same run in `shadow`, on one graph
snapshot. It reads the runs' trace exports — `--file` the shadow run, `--enforce-file` the enforce run, and
`--baseline-file` a second shadow run for the A/A check — joins each trace to a RAG fixture by `userMessage` (exact,
then trim + collapse whitespace + casefold, like `tagging_shadow_report.py`) and by `role` against the fixture's
`actor.role` (a turn asked as another actor, or for a fixture that names none, is not joined: it is no evidence for the
fixture's visibility; one turn scores every fixture that asks its question as its actor; of several turns for one
fixture the latest `startedAt` wins, so a rerun replaces an older attempt), and scores each side's recorded top-K:
hit@k, MRR, recall@k and forbidden-document hits, `k` from the fixture, default 5. Before the join a `--file` or
`--baseline-file` trace must be `SHADOW` and an `--enforce-file` trace `ENFORCE` with `enforced` exactly `[RAG]` (with
`tools`, `card` or `lookups` also enforced, more than the filter would differ); others are skipped and counted
(`wrongMode`).

The gate compares **per fixture**, which is stricter than §9's means: across the 75 fixtures that expect a document one
fixture moves a mean by 1.3 %, so a mean lets one fixture's loss hide behind another's gain. This holds because
retrieval is deterministic once the question tags are (fixed query paraphrases, deterministic fusion and rerank, no LLM
call before the cut), which the A/A check proves for each gate run.

| Verdict            | When                                                                                                       |
| ------------------ | ---------------------------------------------------------------------------------------------------------- |
| `NO_DATA`          | No trace joined a fixture, no joined fixture expects a document (no rank metric to compare), or today's hit@5 is 0 (an empty or broken RAG store would compare 0 with 0). |
| `MIXED_GRAPH`      | The joined traces carry more than one `graphHash`: the evidence is for no single deployable snapshot.      |
| `INCOMPLETE`       | A loaded fixture has no joined shadow trace, or no joined enforce or baseline trace when that run is given. |
| `NONDETERMINISTIC` | The baseline run resolved a fixture differently from the shadow run (top-K order, scope confidence, selected or offered tools): a per-fixture comparison would measure noise. Pin the tagging mode, change nothing between the runs, rerun. |
| `NO_ENFORCE_RUN`   | Complete shadow evidence but no `--enforce-file`.                                                          |
| `INVALID_PAIR`     | A pair's enforce turn resolved another scope confidence than its shadow turn: the runs did not ask the graph the same question. |
| `FAIL`             | Any paired fixture regressed (an expected document of today's top-5 missing from the enforce top-5, or its MRR fell) or surfaced a forbidden document it did not surface today; or a mean hit@5, MRR or recall@5 fell, or the forbidden total grew, overall, in a fixture set (`rag-lexical`, `rag-retrieval`) or in a confidence bucket; or any pair was given different tools. |
| `PASS`             | Otherwise. The exit code is 0 on `PASS` only.                                                              |

§9's "tool selection hit rate at least equal": the RAG fixtures name no expected tools, so a hit rate cannot be scored on
them. The gate checks the stronger property instead — every pair was given the same tools (`selectedTools` and the
`offeredTools` names, order-free); identical selection has an identical hit rate against any ground truth.

The report also prints a **simulated** preview from the shadow run alone: the §6 rule replayed over the shadow top-K (on
`confidence: HIGH` keep a document whose `documentId` is in `scopeDocumentIds` or whose `ragScope` is `master`, on
`LOW`/`NONE` keep everything; order preserved, nothing added). It is not evidence: under `enforce` the retrievers span
every scope, so an in-scope document of another domain, never in the shadow pool, can enter the fusion and outrank a hit,
and the hook filters the pool before the cut, so a candidate below rank K can move up; the replay sees neither. An
expected document it drops is out of scope and not `master`, so the real filter drops it too: use it to decide whether
an enforce run is worth making. Lists (ids under `--verbose`): the regressed fixtures (lost documents, MRR before and
after), tool-selection changes, nondeterministic fixtures, the documents the simulation dropped, forbidden hits,
fixtures without a trace on any side and actor-mismatched turns. Two more sections ride along: a shadow-only tools table
(share of the model's calls that were inside the scope, per confidence; the `tools` consumer is additive and needs its
own gate) and a documentation-coverage table (per seed entity: turns seeded, turns whose scope had no document, mean
scope documents, and with `--lexicon`/`--preload` the static number of RAG documents annotated with the entity, so
"entities with no document" comes out of every run; `--verbose` adds the `NONE`-confidence messages, the vocabulary the
lexicon missed).

Run conditions — what makes the runs differ by the `rag` filter alone:

- **Question tags fixed.** `MCP_TAGGING_MODE` `off` or `shadow` (the heuristic tagger acts) in every run, never
  `enforce`: model-tagger answers vary between runs, and the tags feed the reranker and the scope seeds.
- **One deploy, nothing re-ingested.** Same image, catalog, `entities.yaml`, RAG sources and embedding model in every run;
  `graphHash` covers the graph's document ids, not their content or embeddings.
- **Actors.** Per fixture role, a gate user holding exactly the fixture's `permission_codes` (a trace records the role
  only, so a user with more codes tests the wrong visibility).
- **Same day.** Turn traces expire after 24 h on alpha (`MCP_EVAL_TURN_TRACE_RETENTION`): run all three and the report
  within a day.

```bash
# 1. Shadow run (MCP_SCOPE_GRAPH_MODE=shadow, MCP_TAGGING_MODE off or shadow): every rag-lexical and rag-retrieval
#    fixture query as the actor its fixture names — one scripts/gate_chat_run.sh run per actor role, logged in as that
#    role's gate user, over the fixture files that have queries for it (the runner rejects a file with none).
ROLE=ROLE_SERVICE_ADVISOR; F=".fixtures[] | select(.actor.role == \"$ROLE\")"; args=()
for f in pos-mcp-server/src/test/resources/eval/rag-{lexical,retrieval}/*.json; do
  jq -e "[$F] | length > 0" "$f" > /dev/null && args+=(--fixture "$f")
done
scripts/gate_chat_run.sh --label "scope-shadow-$ROLE" --user <that user> \
  --messages-jq "$F | .query" --ids-jq "$F | .fixture_id" "${args[@]}"
# 2. Baseline: restart, still in shadow, and repeat every role with --label "scope-baseline-$ROLE" (the A/A run also
#    covers the restart the enforce run needs).
# 3. Enforce: restart with MCP_SCOPE_GRAPH_MODE=enforce, MCP_SCOPE_GRAPH_ENFORCE=rag (rag only), tagging mode unchanged,
#    and repeat every role with --label "scope-enforce-$ROLE".
# 4. Score:
python3 scripts/scope_graph_gate_report.py \
  --file gate-runs/scope-shadow-*/traces-*.json \
  --baseline-file gate-runs/scope-baseline-*/traces-*.json \
  --enforce-file gate-runs/scope-enforce-*/traces-*.json \
  --fixture pos-mcp-server/src/test/resources/eval/rag-lexical/*.json \
            pos-mcp-server/src/test/resources/eval/rag-retrieval/*.json \
  --lexicon pos-mcp-server/src/main/resources/scope-graph/entities.yaml \
  --preload pos-mcp-server/src/main/resources/application.yml \
  --verbose            # --json for the machine-readable report
```

A shadow sample whose `scopeDocumentIdsTruncated` is true is counted and flagged (its simulation may drop an in-scope
document; the enforce run reads the whole scope), and traces written before the identity lists existed are skipped. Unit
tests: `python3 -m unittest scripts.test_scope_graph_gate_report` (in `pr-checks.yml`).

**Telemetry.** `nlti.request.telemetry` gained eight additive, nullable fields in `schemaVersion` 2 (`scopeMode`,
`scopeGraphHash`, `scopeConfidence`, `scopeEntityCount`, `scopeToolCount`, `scopeDocCount`, `scopeAddedToolCount`,
`scopeRagFilterApplied`), present only when a scope was resolved. Every version 1 field is unchanged. The event is
`schemaVersion` 3 since ADR-0068 (see Question tagging below).

**Metrics** (registered only when the mode is not `off`): `mcp.scope.resolved{confidence}`,
`mcp.scope.size{kind=entities|tools|documents|screens}`, `mcp.scope.called_tool{in_scope}`,
`mcp.scope.retrieved_doc{in_scope}`, `mcp.scope.fallback{consumer=rag|tools|card|lookups}`, `mcp.scope.errors`. The two
`in_scope` shares are counted when the eval turn trace completes, so they need `mcp.eval.turn-trace.enabled`.

## Question tagging (ADR-0068)

One typed `QuestionTags` record per chat turn, taken **before** the simple-chat decision, the tier routing and the tool
selection, and read by every consumer that used to run its own keyword heuristic: `SimpleChatFastPath`,
`ToolSelectionEngine` (workflow state for session-less callers, the tag-added facades, the scope seeds), the admin fast
path (`ToolRegistryService.resolveCandidateSelection(context, topK, tags)`), the compound split
(`RerankedContentRetriever`, through `RequestScopedUserContext.currentTags()`) and `NltiRouter`, which maps the router
tags to the tier without a chat-model call. In `off` and `shadow` (and for every tag not listed in `enforced-tags`) the
acting answers are the heuristic ones, so every decision but the tier is today's (see Behaviour in `off` and `shadow`);
what changes in `enforce` is below. Two taggers stand behind the seam:
`HeuristicQuestionTagger` (today's rules, moved unchanged; the permanent fallback) and `JevQuestionTagger`, which asks a
Jev-protocol decision model served by the cell's **own** Ollama container at `POST {base-url}/v1/systemone`. **`mode: off`
is the default: the heuristic tagger alone runs, no provider is called, no meter is registered, and no per-turn tagging
log line is written.** The question set (`TaggingQuestions`) is still built once at startup in every mode, so `off` can
log its one-time WARN when the entity lexicon cannot be loaded or the `domain` question is skipped (fewer than 2 or more
than 26 rag-scope options).

| Property                           | Env / Default                                          | Description                                                                                                                                                      |
| ---------------------------------- | ------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `mcp.tagging.mode`                 | `MCP_TAGGING_MODE` `off`                               | `off`: heuristics only. `shadow`: both taggers run, consumers act on the heuristic result, the model's result is recorded. `enforce`: as shadow, plus the tags in `enforced-tags` act. |
| `mcp.tagging.enforced-tags`        | `MCP_TAGGING_ENFORCED_TAGS` _(empty)_                  | Tags (wire names) that act when `mode` is `enforce`, promoted one at a time (ADR-0068 §6); empty behaves as `shadow`. An entry is `<tag>` (symmetric) or `<tag>:veto` (the model may only turn the heuristic's `true` into `false`; a tag the heuristic never answers, `entity_<key>`, therefore never acts under `entity:veto`). `admin_account_question` is veto-only whatever the entry says (§3.4). |
| `mcp.tagging.provider.base-url`    | `MCP_TAGGING_BASE_URL` `http://ollama:11434`           | The System One endpoint. Default: the in-cell `ollama` container, deliberately not `OLLAMA_CHAT_BASE_URL` (hosted on alpha), so by default the message never leaves the cell to be tagged. A hosted System One provider (`https://api.typesafe.ai`, same wire contract) is allowed only under the §4 condition: synthetic data, or a zero-retention DPA recorded in the ADR Changelog. Then `ollama-init` pulls no local model. |
| `mcp.tagging.provider.model`       | `MCP_TAGGING_MODEL` `tev1:0.8b`                        | A decision model pulled into the local container (`tev1:0.8b`, `tev1` or `nimble`), or the hosted provider's own name (`jev-latest`). The §6 bake-off sets the real one. |
| `mcp.tagging.provider.timeout`     | `MCP_TAGGING_TIMEOUT` `800ms`                          | One overall deadline for the one tagging call per turn (connect, response headers and body together, so a trickled body cannot stretch it); on expiry the call is cancelled and the turn takes the heuristic answers. Not raised to fit a slow model (§5). |
| `mcp.tagging.provider.api-key`     | `MCP_TAGGING_API_KEY` _(unset)_                        | Bearer token for the hosted provider only (§4: a DPA with zero data retention first); unset for the local container. Never logged.                               |
| `mcp.tagging.provider.keep-alive`  | `MCP_TAGGING_KEEP_ALIVE` `30m`                         | Sent as `keep_alive` so the local model stays resident beside the embedding model; blank omits the Ollama-only field (set it blank for the hosted provider).   |
| `mcp.tagging.thresholds.<tag>`     | `0.75`                                                 | Per-tag confidence threshold (§1); shadow data sets per-tag values before any promotion. `thresholds.entity` covers every entity Noul; `thresholds.entity.<key>` overrides it for one entity. |
| `mcp.tagging.thresholds.workflow_state.non-idle` | `thresholds.workflow_state`               | The stricter threshold a non-`IDLE` `workflow_state` answer must also meet (the one tag that removes tools, spec §2.6); `thresholds.workflow_state` alone governs an `IDLE` answer. Write dotted keys quoted in YAML (`"workflow_state.non-idle": 0.9`), as for `entity.<key>`. |
| `mcp.tagging.max-state-chars`      | `MCP_TAGGING_MAX_STATE_CHARS` `4000`                   | The message is cut here before it becomes the request `state`; a cut message is still tagged and the cut is counted.                                            |
| `mcp.tagging.entity-questions`     | `MCP_TAGGING_ENTITY_QUESTIONS` `false`                 | Whether the `entity_<key>` Nouls are asked (44 questions, ~18 KB body, about 4.6k tokens) or only the 13 fixed ones (~8 KB, about 2k tokens). They are asked only when this is `true` **and** `mcp.scope-graph.mode` is not `off` (the scope graph is their only consumer). Off by default: the default model `tev1:0.8b` reads about 2,000 tokens, which the wide request overflows. The bake-off decides the setting per model; `optionListHash` reflects the set asked. |

Quote a literal mode in YAML (`"off"`): bare `off` is the boolean `false`.

**Dependency.** `shadow` and `enforce` need Ollama **0.35 or later** in the `ollama` container (the `/v1/systemone`
endpoint shipped there) with the tagging model pulled beside `${OLLAMA_EMBEDDING_MODEL}`, and `OLLAMA_MAX_LOADED_MODELS`
of at least 2 so neither model evicts the other between turns. This branch does not pin or pull them: the compose pin
(`ollama` and `ollama-init` on 0.35.0), the shadow report (`scripts/tagging_shadow_report.py`) and the bake-off procedure
(`pos-mcp-server/src/test/resources/eval/tagging-gate/README.md`) are proposed in PR #2368. Without them, `shadow`
against an older Ollama simply records a `fallback` on every turn.

**The request** carries only `model`, `state` (the message), `keep_alive` and the fixed `questions` (every one with
`instructions`, as Ollama requires): never the caller, the tenant, the history or a forwarded header (§4). The tag set is
closed and lives in code (`TaggingQuestions`): 13 questions by default (~8 KB, about 2k tokens), 44 for today's lexicon
with `entity-questions: true` and the scope graph on (cap 64; ~18 KB body, about 4.6k tokens, for a one-line message;
body cap 64 KiB). Every
instruction starts with one short context clause ("Message from staff at a tire and auto service shop to its management
assistant; may be in English, French or Spanish."):

- twelve fixed tags: `follows_previous_turn`, `simple_chat`, `workflow_state` (a Choice over every `WorkflowState`),
  `needs_web_search`, `about_inventory`, `about_orders`, `implies_date_window`, `admin_account_question`,
  `compound_question`, `intent`, `complexity`, `risk` (a Score over LOW/MEDIUM/HIGH);
- `domain`, a Choice whose options are **permanently the curated RAG-scope vocabulary**: the distinct `rag-scope` values
  of `mcp.rag.preload.docs` plus `master` (15 today; never the 33 tool-catalog domains). Each option's criteria sentence
  comes from the `domains:` block of `scope-graph/entities.yaml`; `ScopeGraphRealConfigValidationTest` requires a sentence
  for every rag-scope of both preload lists and rejects any other key. `TierSelector`'s risky domains (`accounting`,
  `tax`, `admin`, `security`) are spelled in this vocabulary. Tool domains with no rag-scope (`vehicle-inventory`,
  `people-contact`, `supplier`, `marketing`, `location`, `catalog`, `vehicle-fitment`) are never options. A list over
  the local models' 26-option cap skips the question (one WARN at startup), as a list under 2 options does;
- one Noul per lexicon entity, `entity_<key>` (`entity_workorder`), asking "Is this message about any of these: <en
  terms> (French: <fr>; Spanish: <es>)?" from the entity's terms, only when `entity-questions` is true and
  `mcp.scope-graph.mode` is not `off`. An entity Noul yields a seed when `p ≥ 0.5` and its confidence meets
  `thresholds.entity` (or `thresholds.entity.<key>`); the heuristic tagger answers no entity Noul.

The client never logs the state or an answer string; a failure log carries the failure class, HTTP status, host, model
and latency, and of an Ollama `{"error": …}` body only the text's length. Only typed values are read from a response
(§3.6): the provider's own `model` string is ignored, and `providerModel`, the logs and the `{model}` meter tag always
name the configured `mcp.tagging.provider.model`.

**What `shadow` records.** The eval turn trace gains a nullable `tags` (`mode`, `enforcedTags`, `providerModel`,
`latencyMs`, `fallbackReason`, `stateTruncated`, `questionCount`, `requestBodyBytes`, `optionListHash` — the hash of the
domain options and entity keys asked, so agreement on those tags is compared within one hash — and per tag `{name,
actingValue, actingSource, heuristicValue, heuristicRule, modelValue, modelConfidence, modelProbability, threshold,
agree}`, where `threshold` is the one the model answer had to meet (for a non-`IDLE` `workflow_state` answer the
stricter `non-idle` one when higher); `heuristicRule` names the rule that fired where the heuristic exposes one cheaply: `cue:those`,
`phrase:create po`, `keyword:stock`, `word:month`, `implied:revenue`, `named_period:<regex>`, `match:users`,
`veto:invoices`, `sub_queries:2`, `safe_default`, `lexicon:purchase-order` (an entity the message names),
`lexicon_tag:purchase-order` (an entity an acting `entity_<key>` tag seeds); null for the simple-chat catalog); older payloads read `tags: null`.
`nlti.request.telemetry` is `schemaVersion` 3 with a nullable `tagging` block (`mode`, `providerModel`, `latencyMs`,
`fallbackReason`, `agreementRate`, `questionCount`, `requestBodyBytes`, and the acting `intent`, `risk`, `complexity`,
`domain`, `workflowState`, `simpleChat`). The `routing` block keeps its version-2 shape and meaning: its `intentType`,
`riskLevel`, `domain` and `complexity` are the classification the Gate 4 router selected the tier on, present only when
the router ran (tiering on, not a simple-chat turn), as the routing alert rules and the Gate 7 risk panel expect. Since
the router is mapped from the tags (§7) that classification **is** the acting router tags, so on a routed turn those
four fields equal `tagging.intent`, `risk`, `complexity` and `domain`; a turn the router did not route carries them in
`tagging` only. The router calls no model, so `model.routerModel` is absent. The message language is not recorded
(unknown at runtime). Meters (only when the mode is not `off`):
`mcp.tagging.latency{model}`, `mcp.tagging.requests{model,outcome=ok|timeout|error|rate_limited|malformed}`,
`mcp.tagging.fallback{reason}`, `mcp.tagging.agreement{tag,agree}`, `mcp.tagging.state_truncated`.

**Behaviour in `off` and `shadow`.** Every decision is today's decision, from the same rules, now taken once
(`TaggingBehaviourPreservationTest` pins ~75 en/fr/es messages to the pre-refactor fixture), except the tier: the
router's chat-model call is gone in every mode (§7), and the heuristic answers `intent`, `risk`, `complexity` and
`domain` with `safeDefault()`'s values, so with `mcp.model.tiering-enabled` on (off by default, #1683) every routed
turn takes `T2_COMPLEX` until the router tags are enforced. One deliberate change
(§2, §3.1) applies in every mode: a keyword-added facade tool (inventory, orders, date window) is offered only if it is in
the caller's permission-gated set; before, those additions bypassed `mcp_tool_permission` at selection. Two tools are
exempt and behave as before: the glossary (always offered) and web search (offered on `needs_web_search`); neither has
an `mcp_tool_permission` row to intersect with, and neither reads tenant data. Warm-up makes no tagging call: it
passes `QuestionTags.none()` (no provider call, no published record, no tagging meter), and an absent record makes every
consumer behave exactly as `off`, so selection still evaluates today's heuristic rules on the role name, as before
ADR-0068.

**Behaviour in `enforce`.** A tag acts only when it is listed in `enforced-tags`; `mode: enforce` with an empty list
is exactly `shadow`. For a listed tag the acting value is the model's when its confidence meets `thresholds.<tag>` and,
for a `:veto` entry, the heuristic said `true` (acting value = heuristic AND model); otherwise the heuristic's, with
`low_confidence` recorded for that tag on the trace (`fallbackReason` per tag) and under `mcp.tagging.low_confidence{tag}`.
`QuestionTags.enforced(tag)` tells a consumer whether the model's answer is the one acting. What each consumer does:

| Consumer | When the tag acts |
| -------- | ----------------- |
| Simple chat (`SimpleChatFastPath`) | `simple_chat` decides. An enforced `follows_previous_turn` answered `true` forces `false` whatever `simple_chat` says (the T0 path has no history). Promote `simple_chat` as `simple_chat:veto` first: a false positive loses the turn to the history-less path, a false negative costs one LLM turn. |
| Workflow state, session-less callers (`ToolSelectionEngine`) | Precedence (ADR-0068 §3.3): persisted `NltiSession` state (never overridden) → model `workflow_state` at or above threshold (a non-`IDLE` answer also at or above `non-idle`) → the lexicon lookup where the scope graph's `lookups` consumer is enforced **and** the acting `intent` is `ACTION` (the `workflow_state` of an entity the message names or, failing that, of an entity an acting `entity_<key>` tag seeds: "buy 40 tires" tagged `purchase-order` is `CREATING_PO`) → the phrase match. `IDLE` is a value: a model `IDLE` at or above threshold overrides a phrase-matched `CREATING_PO`. `PROCESSING_RETURN` has no heuristic source and is model-only. The lookup runs in `TaggingService` after the merge, because the acting intent is known only then. |
| Tag-added facade tools (`ToolSelectionEngine`) | `implies_date_window`, `needs_web_search`, `about_inventory`, `about_orders` add their facade, each only if it is in the caller's gated set; the glossary tool is always offered. Where `lookups` is enforced and the turn has an entity seed, the inventory / order pair is replaced by the lexicon facades of its seed entities (see Scope graph). Tag-added tools and scope-added tools are unioned on top of the ranked cut; only scope-added ones count against `added-tool-slots` (§3.2). |
| Admin fast path (`ToolRegistryService.resolveCandidateSelection(context, topK, tags)`) | Fires only when an admin keyword or phrase matched without a veto term **and** the acting `admin_account_question` is `true`. A model `false` at or above threshold vetoes it; a model `true` never fires it alone (§3.4). |
| Compound split (`RerankedContentRetriever`, reads the tags from `RequestScopedUserContext`) | An enforced `false` skips the #1180 split; an enforced `true` uses the **widened** splitter: a boundary at a conjunction (`and`, `plus`, `but`, `et`, `y`, `mais`, `pero`) or after `?` / `;` splits without the English starter-word check, so fr and es questions split too (a fragment still needs three tokens). A heuristic answer or `none()` keeps today's splitter. |
| Router (`NltiRouter.classify(message, tags)`) | Maps the acting `intent`, `risk`, `complexity`, `domain` to a `RouterClassification` and lets `TierSelector` pick the tier; the chat-model call is gone (§7). A field whose tag is unlisted or below threshold takes `safeDefault()`'s value (`UNKNOWN`, `HIGH`, `MULTI_DOMAIN`, `master`), so a low-confidence `risk` is `HIGH` and risk never downgrades (§3.5). The `routerChatModel` bean and `mcp.model.router` stay defined until the router tags are promoted. |
| Scope graph seeds (`ScopeResolver.resolve(message, codes, state, tagSeeds)`) | Every acting `entity_<key>` that is `true` seeds its entity with match kind `TAG` (confidence `LOW`); an acting `domain` other than `master` seeds the `Domain` node(s) whose RAG scope it is (the inverse of `domain_scopes`) and, as their one hop, that scope's permitted documents; a Domain is never expanded to tools. |

**The T0 skip (spec §2.5).** When the heuristic `simple_chat` fires on an exact catalog rule (a greeting, thanks,
social question, say-hello or capability phrase within the caps) the provider is not called for that turn, in `shadow`
and `enforce` alike: the record carries `fallbackReason: heuristic_certain`, `latencyMs: 0` and no model answers, and the
turn is counted under `mcp.tagging.skipped{reason=heuristic_certain}`, not under `mcp.tagging.fallback`.

**Promotion order (ADR-0068 §6).** One tag at a time, each after a recorded gate run in en, fr and es: `simple_chat:veto`
first, then the tags that only add tools (`implies_date_window`, `needs_web_search`, `about_inventory`, `about_orders`,
`compound_question`, `follows_previous_turn`), the router tags and `entity` / `domain` (which only widen the scope), and
`workflow_state` **last** of the tool-affecting tags, because a non-`IDLE` state is the one answer that removes tools; the
bake-off reports its false-non-`IDLE` rate separately. `admin_account_question` can only ever veto.

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
it received. A self-hosted provider — infrastructure under our own control — keeps nothing beyond serving this
one request.

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
      AND u.origin = 'CHAT'
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

