# pos-mcp-server

AI orchestration and MCP (Model Context Protocol) server for the Durion Positivity ETSMS platform. It discovers
backend REST APIs from the gateway aggregate OpenAPI spec, registers them as MCP tools, routes natural-language
requests through Spring AI assistants backed by Ollama-compatible chat/streaming models, and maintains a pgvector RAG store for context-augmented
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
- Orchestrate multi-step agent conversations via Spring AI session assistants (synchronous and streaming SSE).
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
   ├─ Spring AI runtime — ChatModel / StreamingChatModel with
   │     tool-calling callbacks + Ollama embedding model
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

The previous external orchestration platform was replaced by in-process orchestration. Prompt construction,
tool-use planning (tool-calling protocol), retrieval, and model abstraction now run locally in the Spring AI
runtime.

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

Permission constants are defined in `McpPermissions`. Errors use the standard `ApiError` envelope.

## Tool Selection

Tool visibility is **permission-gated**, not role-gated. On each chat request `ToolRegistryService.resolveCandidateTools()`
narrows the active tool set:

1. `ToolMetadataRepository.findTopKByEmbeddingForPermissions()` runs a pgvector ANN query (`<=>` cosine distance)
   against `mcp_tool`, `mcp_tool_permission`, and `mcp_workflow_state`. Only tools the caller's `permissionCodes`
   satisfy, and that are valid for the current workflow state, enter the scoring window. Gating happens **inside**
   the query, before the top-K cut.
2. `ToolScorer` ranks candidates by a weighted blend of semantic similarity and normalized priority
   (`Math.clamp(priority, 0.0, 1.0)`).
3. If no embeddings are stored yet, a deterministic fallback returns gated tools sorted by `priority DESC, name ASC`.
4. Selection is capped at `mcp.agent.candidate-tool-limit` (default 8; 16 on `alpha`).
5. `ToolSelectionEngine.fallbackToolsForMessage()` then adds keyword-matched tools **on top of** that cut,
   so they never displace a semantically ranked one: Exa web search (`current`, `news`, `online`, …),
   inventory (`stock`, `sku`, …), order (`order`, `po`, `sale`, …), and — since #1684 — `DateWindowFacadeTool`
   on calendar vocabulary (`month`, `quarter`, `year`, `week`, `days`, `ytd`, `to date`, `since`, …).
   The date-window tool has to be reachable this way: the `DATE_WINDOW` prompt layer requires
   `resolveDateWindow` before every dated tool argument, and nothing in a question like "which customers
   haven't bought in the last 90 days" ranks a date-arithmetic tool description highly, so the embedding
   cut alone would leave the model instructed to call a tool it cannot see.
6. The resolved agent is cached keyed by `role::toolCacheKey` (sorted tool names joined with `+`) and expires after
   `mcp.agent.cache-ttl-minutes` (default 30) so DB priority/prompt edits take effect without restart.

**AND-group gating (V40, #1606).** Every `mcp_tool_permission` row belongs to a `permission_group`, and a facade
tool is offered **iff the caller holds every code of at least one group**. A group is one `@Tool` method's required
permission codes, named after that method (`getCustomer`, `calculateTax`, …). Consequences:

- A composition's group contains only its `.require()`d legs. Optional legs contribute nothing, because
  `ToolComposition` degrades them individually — the section reports its own `not_authorized` status while the rest
  still answer.
- A method that requires no codes (e.g. `CustomerFacadeTool.getCustomerHistory`, which `.require()`s no leg)
  contributes **no group at all**. An empty group would make `bool_and` vacuously true and admit every caller.
- This replaced a flat OR over the union of a tool's codes, under which a composition's _least_-privileged leg
  admitted the whole tool: a technician holding only `workorder:workorder:view` was offered `CustomerFacadeTool`,
  whose `getCustomer` needs `crm:party:view` and 403s downstream (#1606 finding 1).
- Discovered (`source='openapi'`) operations deliberately keep **OR** semantics. That is enforced by the data, not a
  second query: each of their rows is its own singleton group (`permission_group = permission_code`), for which
  AND-within-a-group and OR coincide. `ToolMetadataRepositoryImpl.addToolPermission` writes the same shape.

**Fail-closed:** a tool with zero `mcp_tool_permission` rows is never selected for any caller — the qualifying
predicate is an `EXISTS` over that tool's groups, and `EXISTS` over no rows is false. The `AUTHENTICATED`
sentinel marks operations available to any authenticated caller.

**Permission-code extraction:** `CurrentUserContextResolver` derives bare `domain:resource:action` codes from the
`Authentication` authorities (mixed `ROLE_*`, `PERM_*`, and bare forms) and always includes `AUTHENTICATED`.

> **Workflow state (#778):** both session managers resolve the caller's persisted `NltiSession.workflowState`
> (their most-recently-updated session) and thread it into tool selection, so non-IDLE tool sets (`CREATING_PO`,
> `RECEIVING_ASN`, `INVENTORY_RECON`, `PROCESSING_RETURN`) activate when a session is in that state. Callers with no
> session fall back to message-heuristic derivation. Advance a session's state explicitly via
> `POST /v1/nlt/sessions/{sessionId}/workflow-state` (ownership-checked; guarded by `nlti:request:submit`).

### Facade tools

18 curated facade tools live in `internal/orchestration/tools/`: Accounting, Admin, Catalog, Customer, DateWindow,
Events, Hr, Inventory, Invoice, Location, Order, Pricing, Reporting, ShopManager, Tax, Vehicle, Workorder, and the
always-on Exa web search. The facades are the **primary curated natural-language surface** (#1519): the common
intents — lookup, search, status, and summary per domain — are answerable through facade tools alone, while the
OpenAPI-discovered operations (next section) complement the long tail. Every `@Tool` method calls a real backend
endpoint via a `@LoadBalanced` RestClient through the gateway, with one exception: `DateWindowFacadeTool`
(#1675) makes no HTTP call at all — it resolves a relative date range to concrete dates with pure `java.time`
arithmetic (`DateWindowResolver`) off the shared `Clock` bean, so every other date-taking tool call is preceded
by a resolver round instead of model-computed dates. The other split client is TaxFacadeTool, which uses
both: a direct (non-load-balanced) RestClient to `pos-tax:8091` for the calculate leg — pos-tax is internal-only and
unreachable via Eureka or the gateway (ADR-0021, #641) — and a load-balanced gateway client for everything pos-tax
does not serve (the location lookup feeding the calculation, and the accounting tax-liability report behind
`getTaxSummary`).

**Composition tools.** Where no single service publishes the resource a facade names (shop status, financial
summary, price-for-SKU, …), the facade coordinates multiple real service calls (`ToolComposition`) and returns a
sectioned JSON envelope: `{"composition":..,"status":..,"sections":{..},"sources":[..]}` with `status` `ok` or
`degraded`. Each downstream leg renders its own section; a failed leg degrades the answer instead of failing the
tool, and a 401/403 leg renders as `not_authorized` without relaying the downstream response body. The current
compositions and their legs:

| Tool                  | Downstream legs                                                                                                                               |
| --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `getFinancialSummary` | accounting income-statement + balance-sheet + trial-balance                                                                                   |
| `getRevenueReport`    | accounting income-statement (revenue lines) + aged-receivables                                                                                |
| `getCustomerHistory`  | CRM snapshot + interactions + invoice line-item search by `partyId` (de-duped by invoice) + workorder search by customer                      |
| `getShopStatus`       | location record + shop-manager schedule board + workorder workexec WIP                                                                        |
| `getShopQueue`        | workorder workexec WIP + shop-manager schedule board                                                                                          |
| `getPriceForSku`      | catalog detailed product search (active MSRP); a supplied `locationId` adds a dependent effective-price leg fed by the first leg's product id |
| `calculateTax`        | gateway location lookup (destination address) + direct pos-tax `POST /v1/tax/calculate`                                                       |
| `getTaxRate`          | gateway location lookup (destination address) + direct pos-tax `GET /v1/tax/rates`                                                            |

**Contract chain.** What keeps the facades honest: every `@Tool` method's verb + path lives in
`src/test/resources/facade-contract.yaml` (compositions list every leg), and facade tests derive their
MockRestServiceServer expectations from that manifest — never from string literals duplicating the configuration —
with `FacadeContractManifestTest` locking each manifest template to its `application.yml` default. Independently,
`scripts/check-mcp-facade-paths.py` resolves every configured template and manifest entry through the gateway route
table and validates verb + path against the routed module's `openapi.yaml` (route-aware, verb-checking, with
enum-expansion annotations for constrained path segments like the event-summary window). The checker runs in CI
(`.github/workflows/pr-checks.yml`) with `scripts/mcp-facade-paths-baseline.json` gating new breaks — the baseline
is currently empty, so any new mismatch fails the build.

**Deferred methods.** None. The three methods previously removed for lacking a real backend endpoint —
`getEventHistory` (#1521), `getTaxRate` (#1522), and `searchEmployees` (#1523) — are all restored: pos-event-receiver
now serves per-entity event history (`GET /v1/events?entityId=`), pos-tax now serves a jurisdiction rate lookup
(`GET /v1/tax/rates`), and pos-people now serves employee search (`GET /v1/people/employees?q=`).

Permission mappings for these tools are seeded by migration `V18` (retargeted by `V35`/`V36`); the #1519
re-derivation migration (`V37`) re-derives the seeds against the restored targets above, `V38` adds
`tax:rates:view` to TaxFacadeTool for the restored `getTaxRate`, `V39` re-derives AccountingFacadeTool for the W1.2
aging methods, and `V40` (#1606) repartitions all 16 facades into per-method AND-groups. `V40`'s header carries the
full tool → group → codes derivation table; `FacadeToolPermissionSeedTest` replays the whole chain and asserts it.

The seed mirrors each downstream controller's _declared_ authorization, not the product intent of the facade: for
every backend endpoint a `@Tool` method calls, the merged class + method `@PreAuthorize` is read and
`hasAuthority('X')` / `hasAnyAuthority('X','Y')` contribute codes `X`, `Y`. Since `V40` those codes are grouped per
`@Tool` method rather than unioned across the tool class, and only a composition's `.require()`d legs contribute.
Some facade reads therefore fall back to the `AUTHENTICATED` sentinel instead of a "normal" business
permission code:

- **`isAuthenticated()` or no `@PreAuthorize` at all** (e.g. Order and Pricing reads, EventSummaryController) — there
  is no permission-coded guard for MCP to copy, so the seed uses the `AUTHENTICATED` sentinel by design. Note this
  reflects MCP's own selection gate, not a claim about the downstream endpoint: an endpoint with no `@PreAuthorize`
  (like EventSummaryController) declares no gate of its own.
- **Role-only guards** (`hasRole(...)`, e.g. Catalog reads) — `mcp_tool_permission` stores permission codes, not role
  names, so role-only controller guards cannot be mirrored here; the role gates are still enforced in the downstream
  service via Spring Security.

> **Do not edit `V18` (or any applied migration) in place** — even comment-only changes alter the Flyway checksum and
> fail validation on deployed environments. Document rationale here or add a new migration instead. (This is also why
> V18's in-file comment saying role gates are "enforced separately at the gateway" is left as-is despite being
> imprecise — the corrected statement is the one above: role authorization happens in the downstream service's
> Spring Security, the gateway only authenticates and forwards identity headers.)

### OpenAPI-discovered tools

`ToolBootstrapRunner` calls `ToolRegistrationService.registerDiscoveredTools()` on startup. `OpenApiDocumentFetcher`
pulls the gateway aggregate spec (`pos-api-gateway`, configurable via `MCP_AGGREGATE_SPEC_URL`), `OpenApiToolMapper`
maps operations to `mcp_tool` rows, and `OperationProxyFactory` builds the HTTP proxy used to execute a call.
`OpenApiToolProvider` then resolves permission-gated discovered operations into dynamic Spring AI `ToolCallback`s per
request. These expand the candidate pool beyond the 17 facades.

Discovery runs once at startup. Set `mcp.server.discovery-refresh.enabled=true` (interval
`mcp.server.discovery-refresh.interval-ms`, first run after `mcp.server.discovery-refresh.initial-delay-ms`, both
default 5 min) to periodically re-discover and pick up new or changed backend operations without a restart —
re-registration is idempotent (each tool is removed then re-added). The **alpha** profile enables it at a 30-minute
interval with a 5-minute initial delay (#1632 follow-up) so a domain whose fetch and same-cycle fallback both failed
self-heals without a restart — and does so shortly after the deploys that cause stale routes; other profiles leave
it off.

**Spec-identity guard (#1632).** After a rolling deploy, a stale Eureka registration can route a domain's doc URL to a
_different_ service (on alpha, `/invoice/v3/api-docs` briefly served pos-price's spec — 200 OK and parseable, so no
transport or parse guard fires). Discovery therefore verifies each fetched per-service spec's `info.title` against its
routing token and treats a mismatch as a **failed fetch**: the wrong domain's ops are not registered under the prefix,
and the domain's previously-registered ops are kept, not pruned. Titles that are missing, blank, or springdoc's default
(`OpenAPI definition`) are unverifiable and always pass. Domains whose title doesn't contain their routing token get
extra accepted tokens via `mcp.server.spec-identity-aliases` (shipped defaults: `catalog: [product]`,
`people: [human resources]` — keep these in sync if a service's OpenAPI title changes; keys may be spelled as the
routing token, `vehicle-fitment`, or its normalized form, `vehiclefitment`). The guard is best-effort: it cannot
catch a stale route between token-nested domains (people-contact's title served at `/people`, workorder's at
`/order`, vehicle-inventory's at `/inventory` all pass containment) or a service with no configured title — in those
cases behavior is simply no worse than before the guard existed.

**Unseen-domain guard (#1819).** A registered domain that contributes no operation in a discovery cycle is kept, not
pruned, on every path — on the gateway-aggregate path nothing "fails" when a service is down mid-deploy; it is simply
absent from the aggregate, and on alpha (2026-09-06) an aggregate carrying one service's 70 ops pruned the other 965
rows as stale, after which the next container re-embedded them all before readiness (#1818). Each such cycle logs one
ERROR naming the failed-fetch domains and the unseen domains separately (`registered but contributed no operation
this cycle (#1819, kept not reconciled)`). A domain that is genuinely gone — retired service, renamed gateway prefix —
will repeat that line every cycle until an operator lists it in `mcp.discovery.prunable-when-unseen`
(`MCP_DISCOVERY_PRUNABLE_WHEN_UNSEEN`, comma-separated `mcp_tool.domain` keys), after which one cycle reconciles it.

## Answer Resolution

The model's reply is classified before it reaches the user (`ChatResponseText`): direct `CONTENT`,
`BLANK` (nothing but a reasoning channel), or `TOOL_PAYLOAD` (a bare JSON object or array — the
model emitted a tool result instead of composing from it). Non-content replies never reach the user
as-is. `BLANK` goes to the answer-resolution ladder (deep link / capability hint). `TOOL_PAYLOAD`
first gets **one re-render turn** (#1708): the payload is fed back as the previous assistant message
with an instruction to answer the question from it as prose and never as JSON, with no tools offered,
so it cannot loop; the instruction says the JSON may be partial or a call's arguments and forbids
inventing figures. If that turn is direct content it is the reply, otherwise the ladder answers as for
`BLANK`. Log lines: `Bare tool payload re-rendered as prose (#1708)` on success (the first turn's
"no direct answer" warning is then suppressed); on failure, `re-render produced no direct answer
either: source=…` followed by the usual first-turn warning. The render turn carries the conversation
history but not the layered system prompt or RAG block, and costs a second full model call.

## Audit & Adaptive Tuning

Every tool decision is logged (selected tool, semantic rank, final score, `selected`, `success`, `fallback_invoked`,
latency). A daily cron (`mcp.tuning.cron`, default `0 0 2 * * ?`) recomputes per-tool performance and adjusts
`mcp_tool.priority`:

```
performance_score = (success_rate * 0.6) + ((1 - normalized_latency) * 0.3) + ...
```

Tuning is enabled by default with a runtime kill switch (`mcp.tuning.enabled`). Owning classes: `ToolAuditService`,
`ToolPriorityTuningService`.

## Offline Replay Eval (#1682)

Alpha-only, property-gated (`mcp.eval.turn-trace.enabled`, default on for alpha) capture of one immutable
JSON trace per completed/failed chat turn — assembled system prompt, offered tool definitions, ordered
tool calls with arguments/results, the final response or error, and the build that answered
(`serverBuild`, from `MCP_BUILD_ID`, the image tag on alpha; #1806) — persisted with a configurable
retention window and cleaned up on a schedule. Twelve committed fixtures replay the live analytics gate's
`in_chat_path_gate` questions against a real model with canned tool responses, so a candidate fix is
verifiable without an alpha deploy-and-run cycle. See `src/test/resources/eval/README.md` (§"Offline
replay eval") for capture, fixture, replay and grading details, and `OfflineReplayEvalIT` /
`scripts/analytics_gate_run.py --replay-report` to run it.

`mcp_tool_invocation_log` records _that_ a tool ran, never its arguments — those carry customer identifiers. One
exception is logged rather than stored, because its arguments carry none: `DateWindowFacadeTool` emits an INFO line
per resolution (#1684),

```
MCP date window resolved shape=CALENDAR_SPAN unit=MONTH count=12 comparison=NONE startDate=2025-09-01 endDate=2026-08-31
```

so the window a turn actually used can be graded on its own. The shape is the classification the model makes and the
one that has been getting the analytics gate's dated questions wrong; once the window leaves this tool it is a pair of
dates in some other tool's arguments, indistinguishable from a correctly shaped one. A rejected classification logs
nothing — no window was resolved.

## RAG Retrieval Pipeline (Tier 2)

Both session managers use a Tier-2 retrieval chain:

1. Baseline semantic retriever (`maxResults=10`, `minScore=0.6`).
2. Query-expanded retriever (`maxResults=20`, `minScore=0.55`) using deterministic paraphrases.
3. PostgreSQL full-text retrieval for literal identifiers (enabled by default), followed by reciprocal-rank fusion and de-duplication across all retrievers.
4. Final lexical-aware re-ranking to the top-5 contexts before prompt injection.

Retrieval is role-aware (`RoleAwareMetadataFilter`, `ScopedContentRetrieverFactory`) and chat memory is persisted via
`SemanticChatMemoryStore` with session summarization (`SessionSummary`).

### Troubleshooting identifier misses

Treat a missing VIN, SKU, event name, or permission code as a candidate-pipeline problem before
changing the answer prompt:

1. Run the same query against dense retrieval at both score floors and against PostgreSQL FTS;
   record document IDs at every stage (source retrieval, RRF, de-duplication, and final top-5).
2. Confirm the expected chunk has the selected tool's RAG scope and that its
   `required-permissions` are satisfied. A correct lexical match must never bypass scope or
   permission filtering.
3. Inspect the stored `search_vector` and query produced by `websearch_to_tsquery`. Punctuation in
   VINs, SKUs, and colon/dot-delimited codes can change lexemes; use a normalized exact-token
   fallback when PostgreSQL tokenization removes the distinguishing characters.
4. Compare the covering chunk's rank before and after `RerankedContentRetriever`. If it enters the
   candidate pool but misses the top-5, tune or diversify the final selection rather than lowering
   the dense threshold.
5. Check ingestion and chunking: verify the current source revision was embedded, the literal token
   was not split across chunks, and dense/lexical copies share a stable document ID for de-duplication.

Keep exact-identifier fixtures in `src/test/resources/eval/rag-lexical` and compare dense-only with
hybrid recall, hit@5, MRR, forbidden-document violations, and latency before changing fusion weights
or similarity floors.

## Configuration

| Property                                    | Env / Default                               | Description                                                                                                                                                                                                                                                                                                                                                                |
| ------------------------------------------- | ------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `spring.ai.ollama.chat.options.model`       | `OLLAMA_CHAT_MODEL` `gpt-oss:120b`          | Deliberate default executor model (single default; tier routing may override per request)                                                                                                                                                                                                                                                                                  |
| `spring.ai.ollama.chat.options.num-ctx`     | `OLLAMA_NUM_CTX` `32768`                    | Context window, sent to Ollama as `num_ctx`. Defaulted here for every profile so it is never inherited from the backend (#1683) — Ollama silently drops the front of the context, i.e. the system prompt, once the prompt exceeds the window. A request, not a guarantee: a backend may cap it (see the runbook's truncation check). Override per host; don't set it empty |
| `spring.ai.ollama.chat.options.temperature` | `OLLAMA_CHAT_TEMPERATURE` `0.0`             | Executor sampling temperature. 0 by default: the analytics gate is graded at n=1, so sampling only adds run-to-run variance                                                                                                                                                                                                                                                |
| `mcp.model.fallback.secondary-model-name`   | `OLLAMA_FALLBACK_MODEL` `gpt-oss:20b`       | Secondary model used when `mcp.model.fallback.enabled=true` (inherits the primary's temperature and `num_ctx`)                                                                                                                                                                                                                                                             |
| `OLLAMA_CHAT_THINK`                         | _(unset)_                                   | `false`/`true` to force Ollama thinking off/on; unset leaves the model default. Set `false` for reasoning models that would otherwise return the answer in the `thinking` channel (blank `content`)                                                                                                                                                                        |
| `spring.ai.ollama.embedding.options.model`  | `OLLAMA_EMBEDDING_MODEL` `nomic-embed-text` | Embedding model for RAG                                                                                                                                                                                                                                                                                                                                                    |
| `mcp.agent.cache-ttl-minutes`               | `30`                                        | Agent cache TTL (role agents + sessions)                                                                                                                                                                                                                                                                                                                                   |
| `mcp.agent.candidate-tool-limit`            | `MCP_AGENT_CANDIDATE_TOOL_LIMIT` `8`        | Max candidate tools per chat request                                                                                                                                                                                                                                                                                                                                       |
| `mcp.rag.chunking.enabled`                  | `MCP_RAG_CHUNKING_ENABLED` `true`           | Chunk documents before embedding                                                                                                                                                                                                                                                                                                                                           |
| `mcp.rag.chunking.max-segment-size`         | `MCP_RAG_MAX_SEGMENT_SIZE`                  | Max chunk size                                                                                                                                                                                                                                                                                                                                                             |
| `mcp.rag.chunking.max-overlap-size`         | `MCP_RAG_MAX_OVERLAP_SIZE`                  | Chunk overlap                                                                                                                                                                                                                                                                                                                                                              |
| `mcp.rag.hybrid.lexical-enabled`            | `MCP_RAG_LEXICAL_ENABLED` `true`            | Include scoped PostgreSQL full-text hits in RRF fusion; set `false` for immediate rollback                                                                                                                                                                                                                                                                                 |
| `mcp.rag.preload.docs`                      | `[]`                                        | Static classpath documents to preload                                                                                                                                                                                                                                                                                                                                      |
| `mcp.tuning.enabled`                        | `MCP_TUNING_ENABLED` `false`                | Adaptive tool priority tuning (disabled until regression harness exists — Gate 0)                                                                                                                                                                                                                                                                                          |
| `mcp.tuning.cron`                           | `0 0 2 * * ?`                               | Tuning schedule (daily 02:00)                                                                                                                                                                                                                                                                                                                                              |
| `mcp.model.fallback.enabled`                | `MCP_MODEL_FALLBACK_ENABLED`                | Primary → secondary model fallback                                                                                                                                                                                                                                                                                                                                         |
| `mcp.model.tiering-enabled`                 | `MCP_MODEL_TIERING_ENABLED` `false`         | Gate 4 tier routing. **Dormant** (#1683): with `mcp.model.simple`/`complex` blank both T2 tiers resolve to the same model, so enabling it only pays for a per-turn classification call whose outcome cannot change which model answers                                                                                                                                     |
| `mcp.model.simple`                          | `MCP_MODEL_SIMPLE` _(blank)_                | T2-simple executor. Blank = the default executor model. Setting it to a genuinely smaller pulled model is the precondition for turning tiering back on                                                                                                                                                                                                                     |
| `mcp.discovery.aggregate-spec-url`          | `MCP_AGGREGATE_SPEC_URL`                    | Gateway aggregate OpenAPI URL                                                                                                                                                                                                                                                                                                                                              |
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

- **Legacy role-gating cleanup** — drop `mcp_role` / `mcp_tool_role`, `ToolRegistryRoleMapper`, and the role-gated
  repository queries now superseded by permission gating.
- **`AUTHENTICATED` sentinel everywhere** — promote `requiredPermissionsOperationCustomizer` to `pos-security-common`
  and emit the sentinel from all services so unguarded operations gate correctly.
- **Retrieval-quality regression tests** — hit@5 / MRR harness for tool-selection and RAG recall.
- **Hybrid embedding + BM25 retrieval** and an **admin UI for `mcp_tool_permission`** maintenance.

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
