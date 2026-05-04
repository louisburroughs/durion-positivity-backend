# Natural Language Query to API: Architecture Analysis

> Covers tool discovery, RAG usage, NLQ→API translation, improvement recommendations, database access
> design, and a model appraisal for llama3.1:8b.
>
> Generated from code review of `pos-mcp-server` as of 2026-05-03.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Request Entry Points](#2-request-entry-points)
3. [Request Flow: Blocking Chat](#3-request-flow-blocking-chat)
4. [Request Flow: Streaming Chat](#4-request-flow-streaming-chat)
5. [Tool Discovery System](#5-tool-discovery-system)
6. [Semantic Tool Selection](#6-semantic-tool-selection)
7. [NLQ to API Call Translation](#7-nlq-to-api-call-translation)
8. [RAG Pipeline](#8-rag-pipeline)
9. [SimpleChatClassifier: The Fast-Path Gate](#9-simplechatclassifier-the-fast-path-gate)
10. [Two Separate Tool Systems](#10-two-separate-tool-systems)
11. [Improvement Recommendations](#11-improvement-recommendations)
12. [Ad-Hoc Database Query Access](#12-ad-hoc-database-query-access)
13. [Model Appraisal: llama3.1:8b](#13-model-appraisal-llama318b)

---

## 1. Architecture Overview

The MCP server is a Spring Boot application that acts as a conversational gateway to the broader Positivity
POS microservice ecosystem. Its core job is to accept a natural-language message from an authenticated user,
decide which backend APIs are relevant to answer it, call those APIs, and return a synthesized response.

The following major subsystems compose the pipeline:

| Subsystem                                          | Purpose                                                                  |
| -------------------------------------------------- | ------------------------------------------------------------------------ |
| `McpChatController` / `McpStreamingChatController` | HTTP entry points; JWT auth and role extraction                          |
| `SessionAgentManager`                              | Per-user rate limiting, simple-chat gate, tool selection, agent dispatch |
| `SimpleChatClassifier`                             | Regex+rule gate: routes greetings/social to a no-tool fast path          |
| `ToolRegistryService`                              | Semantic (embedding-based) tool ranking from the database                |
| `ToolRegistry` / `ToolRegistryLoader`              | DB-backed, Spring-bean-resolved tool instance map per role               |
| `PosAssistant` / `StreamingPosAssistant`           | LangChain4j AiServices proxy; wraps the model+tools+RAG                  |
| `DocumentEmbeddingIngestor`                        | Splits, embeds, and stores documents into PgVector                       |
| `EmbeddingStoreContentRetriever`                   | At inference time, retrieves RAG chunks relevant to the query            |
| `ToolRegistrationServiceImpl`                      | Eureka+OpenAPI discovery → MCP protocol tool registration                |

---

## 2. Request Entry Points

### `McpChatController` (blocking, `POST /v1/mcp/chat`)

```
client → JWT auth filter → McpChatController.chat()
    → extractPrimaryRole(authentication)   // priority-ordered: ADMIN > MANAGER > ... > USER
    → AgentOrchestrationService.chat(userId, role, message)
    → ResponseEntity<ChatResponse>
```

Role extraction is deterministic: a priority-ordered list (`ROLE_ADMIN`, `ROLE_MANAGER`, `ROLE_SERVICE_WRITER`,
`ROLE_CASHIER`, `ROLE_SUPPLIER`, `ROLE_TECHNICIAN`) picks the highest role present in the JWT authorities.
If none match, `ROLE_USER` is returned as a fallback. The full authority set is logged at DEBUG.

### `McpStreamingChatController` (SSE, `POST /v1/mcp/chat/stream`)

Identical auth and role logic. Returns `Flux<ServerSentEvent<String>>` — one SSE event per token, with
`event: chat`. The streaming path delegates to `StreamingAgentOrchestrationService`, which uses a
`StreamingChatModel` and `TokenStream` instead of blocking `ChatModel`.

---

## 3. Request Flow: Blocking Chat

This is the most complete path and contains all the interesting decision logic.

```
SessionAgentManager.chat(userId, role, message)
│
├─ [1] Rate limit check
│    requestCountCache.get(userId) → AtomicInteger
│    if count > 100 (configurable) → throw RateLimitExceededException
│
├─ [2] Simple-chat gate
│    SimpleChatClassifier.isSimpleChat(message)
│    if TRUE → simpleChat() [direct model call, no tools, no RAG]
│    if FALSE → continue
│
├─ [3] Semantic tool selection
│    ToolRegistryService.resolveCandidateTools(
│        ToolSelectionContext(message, role, "IDLE"), topK=2)
│    → returns List<ToolMetadata> (scored and ranked)
│
├─ [4] Resolve tool beans
│    ToolRegistry.resolveToolsForRole(role, selectedNames)
│    → Spring bean instances matching the selected tool names
│    If result is empty → fall back to full role tool set
│
├─ [5] Keyword-based fallback tools
│    SessionAgentManager.fallbackToolsForMessage(message)
│    → regex word matching adds ExaWebSearchTool, InventoryFacadeTool, OrderFacadeTool
│
├─ [6] Merge tools (dedup by @Tool method name)
│    ToolSelectionSupport.mergeWithoutDuplicateToolNames(roleTools, fallbackTools)
│
├─ [7] Get or create agent (Caffeine cache key = role + sorted tool names)
│    AiServices.builder(PosAssistant.class)
│        .chatModel(llama3.1:8b @ Ollama)
│        .tools(mergedTools)
│        .contentRetriever(ResilientContentRetriever → EmbeddingStoreContentRetriever)
│        .systemMessageProvider(role → DB system prompt)
│        .chatMemoryProvider(userId::role → MessageWindowChatMemory(50))
│        .build()
│
└─ [8] Invoke agent
     agent.chat(memoryKey(userId, role), message, "Current user role: " + role)
     → LangChain4j orchestrates the full tool-calling loop (see §7)
     → returns String
```

### Memory Key

Chat memory is keyed `userId::role`. This means a user who gets a different role assignment in a new session
gets a fresh conversation window — which is likely intentional isolation but worth noting.

---

## 4. Request Flow: Streaming Chat

The `StreamingSessionAgentManager` is a simplified variant:

- **No simple-chat gate** — all messages go through the full tool path.
- **No semantic tool selection** — uses the full role tool set from `ToolRegistry` plus `ExaWebSearchTool`.
- **RAG is present** — configured identically to the blocking path (maxResults=5, minScore=0.7).
- **Agent cache key is just `role`** — one agent per role, not per tool selection.

This creates a notable behavioral asymmetry: streaming users always receive the broadest tool set regardless of
query intent, while blocking users get a focused (topK=2) selection.

---

## 5. Tool Discovery System

There are two entirely separate tool discovery and registration systems in this codebase. This distinction is
critical to understanding the architecture.

### System A: MCP Protocol Tool Registration (Eureka + OpenAPI)

**Actors**: `ToolBootstrapRunner`, `ToolRegistrationServiceImpl`, `OpenApiDocumentFetcher`, `OpenApiToolMapper`

**What it does**:

1. At startup (`ToolBootstrapRunner`), Eureka's `DiscoveryClient` enumerates all registered services.
2. Services on the configured allowlist (`mcp.server.included-services`) are fetched for their OpenAPI spec
   (`/v3/api-docs` path, configurable).
3. `OpenApiToolMapper` converts OpenAPI operations matching configured path prefixes into
   `McpServerFeatures.AsyncToolSpecification` objects.
4. These are registered on `McpAsyncServer` via the MCP protocol's SSE transport.
5. A `notifyToolsListChanged()` event is published to update connected MCP clients.

**Purpose**: This exposes backend service APIs as MCP-protocol tools to _external MCP clients_ (e.g., IDE
plugins, Claude Desktop, other MCP-aware consumers) via the SSE transport at `/mcp/sse`.

**Current scope**: Only `event-receiver` and paths under `/v1/events/summary` are on the default allowlist
(`application.yml`). This system is underutilized relative to its architecture.

### System B: LangChain4j @Tool Beans (DB + Spring Context)

**Actors**: `ToolRegistryLoader`, `ToolRegistry`, `ToolEmbeddingInitializer`, `ToolRegistryService`

**What it does**:

1. At startup, `ToolRegistryLoader.loadRoleToolMappings()` queries `mcp_tool` for all enabled tools per role
   with workflow state `IDLE`.
2. Each tool's `handler_bean` column value is used to look up a Spring bean from `ApplicationContext`.
3. The resulting `Map<String, List<Object>>` (role → bean instances) is stored in `ToolRegistry`.
4. `ToolEmbeddingInitializer` runs at startup and populates the `embedding` column of `mcp_tool` for any tool
   whose description has not yet been vectorized (using `nomic-embed-text`, 768 dimensions).
5. At request time, `ToolRegistryService.resolveCandidateTools()` uses these embeddings for ANN search.

**Purpose**: These are the tools injected into the LangChain4j agent that processes user chat messages.
They are concrete Java classes (e.g., `InventoryFacadeTool`, `OrderFacadeTool`) with `@Tool`-annotated
methods that make REST calls to downstream microservices.

### Tool Metadata Schema

The `mcp_tool` table, represented by `ToolMetadata`, holds:

| Column         | Purpose                                                                         |
| -------------- | ------------------------------------------------------------------------------- |
| `id`           | UUID primary key                                                                |
| `name`         | Matches the Java class simple name (e.g., `InventoryFacadeTool`)                |
| `description`  | Human-readable description — **this is what gets embedded for semantic search** |
| `domain`       | Business domain (e.g., `inventory`, `orders`)                                   |
| `priority`     | Double weight applied during scoring                                            |
| `costLevel`    | `low`/`medium`/`high` — penalized in scoring                                    |
| `avgLatencyMs` | Used as a latency penalty in scoring                                            |
| `enabled`      | Whether the tool is active                                                      |
| `handlerBean`  | Spring bean name for resolution via `ApplicationContext.getBean()`              |

---

## 6. Semantic Tool Selection

`ToolRegistryService.resolveCandidateTools()` is called on every non-simple-chat request in the blocking path.
Here is the complete algorithm:

```
1. Load gated tools: findEnabledByRoleAndWorkflow(role, "IDLE")
   → database query returning List<ToolMetadata>
   → if empty, return []

2. Admin fast-path check:
   if role == ROLE_ADMIN AND userInput matches any of:
       keywords: user, users, role, roles, permission, ..., login
       phrases: "who has access", "audit log", "user count", ...
   → return [AdminFacadeTool] immediately (bypass embedding)

3. Embed user input:
   float[] embedding = embeddingModel.embed(userInput).content().vector()
   (nomic-embed-text model, 768-dimensional vector, via Ollama)

4. ANN search:
   semanticLimit = max(topK, 10)  // always search at least 10
   semanticCandidates = repository.findTopKByEmbedding(embedding, semanticLimit)
   (pgvector cosine similarity query on mcp_tool.embedding)

5. Gate filter:
   Keep only semanticCandidates whose UUID is in the gated tool set

6. Score each candidate:
   semanticScore = 1.0 / (rankPosition + 1)   // reciprocal rank
   priorityBoost = tool.priority               // raw value from DB
   latencyPenalty = min(avgLatencyMs / 1000.0, 1.0) * 0.2
   costPenalty = 0.2 (high) | 0.1 (medium) | 0.0 (low)
   total = semanticScore + priorityBoost - latencyPenalty - costPenalty

7. Sort by total score descending, then by rankPosition ascending, then by name

8. Return top K (default topK=2 in alpha profile)
```

The returned `List<ToolMetadata>` contains only metadata — the caller (`SessionAgentManager`) then resolves
the actual Spring bean instances via `ToolRegistry.resolveToolsForRole(role, selectedNames)`.

---

## 7. NLQ to API Call Translation

This is the critical section: how a sentence like _"What's the stock level for SKU-9942?"_ becomes a
concrete HTTP call to `pos-inventory`.

### Step 1 — Agent Assembly (once per unique tool set per role)

LangChain4j's `AiServices.builder()` reflects on all `@Tool`-annotated methods across the injected tool beans
and generates JSON Schema function definitions for each. For `InventoryFacadeTool`, this produces something like:

```json
{
  "name": "checkStock",
  "description": "Check current stock level for a product by SKU number",
  "parameters": {
    "type": "object",
    "properties": {
      "sku": { "type": "string", "description": "The SKU number to look up" }
    },
    "required": ["sku"]
  }
}
```

These function schemas, along with the system prompt and any retrieved RAG context, are sent to the model
in the first turn.

### Step 2 — RAG Context Injection (per request)

Before the model receives the user's message, `ResilientContentRetriever` runs:

```
userMessage → embeddingModel.embed(userMessage)
           → pgvector ANN search (maxResults=5, minScore=0.7)
           → List<Content> (text chunks from mcp_document_embedding table)
```

LangChain4j prepends these chunks to the prompt as context so the model can reference stored domain knowledge
(e.g., bookkeeping rules, inventory control procedures) without needing to call a tool.

### Step 3 — First Model Call

The model receives:

```
SYSTEM: [role-specific system prompt from DB]
         Current user role: ROLE_CASHIER
CONTEXT (RAG): [0-5 retrieved document chunks]
USER: What's the stock level for SKU-9942?
TOOLS: [JSON schemas for all injected @Tool methods]
```

Temperature is set to 0.2. The model produces either:

- A direct text response (no tool needed), or
- A tool call: `{ "name": "checkStock", "arguments": { "sku": "SKU-9942" } }`

### Step 4 — Tool Execution

LangChain4j intercepts the tool call response. It:

1. Finds the `InventoryFacadeTool` bean instance.
2. Invokes `checkStock("SKU-9942")` via reflection.
3. The method executes: `restClient.get().uri("/stock/SKU-9942").retrieve().body(String.class)` → HTTP GET to
   `pos-inventory`.
4. Returns the JSON response body as a `String`.

### Step 5 — Second Model Call (synthesis)

The tool result is appended to the conversation:

```
TOOL_RESULT (checkStock): { "sku": "SKU-9942", "quantity": 47, "location": "Warehouse A" }
```

The model synthesizes this into a natural language response:

> "SKU-9942 currently has 47 units in stock at Warehouse A."

If the model decides multiple tools are needed, steps 4–5 repeat for each tool call.

### Tool Method Annotations Drive Everything

The quality of the translation depends entirely on how well the `@Tool` and `@P` annotation text describes
each method:

```java
@Tool("Check current stock level for a product by SKU number")
public String checkStock(@P("The SKU number to look up") String sku) { ... }
```

The description in `@Tool` is what the embedding was generated from for semantic selection. The description
in `@P` guides the model's argument extraction from natural language. Both are load-bearing for accuracy.

---

## 8. RAG Pipeline

### Ingestion

`DocumentEmbeddingIngestor.ingestDocument(content, metadata)`:

```
1. If metadata contains document_id → replace existing chunks for that ID
   Else → generate new UUID

2. DocumentSplitters.recursive(maxSegment=2000, overlap=200)
   → List<TextSegment> (chunked, with metadata: document_id, chunk_index, chunk_count)

3. embeddingModel.embedAll(segments)
   → List<Embedding> (768-dim float arrays via nomic-embed-text)

4. embeddingStore.removeAll(filter by document_id)  [if replacing]
   embeddingStore.addAll(embeddings, segments)
   → stored in PostgreSQL mcp_document_embedding table (pgvector)
```

Static documents (e.g., `rag/de-bookkeeping-rag.md`, `rag/inv-cntrl-rag.md`) are preloaded at startup
via `StaticRagPreloadServiceImpl` (profile: alpha), with SHA-256 deduplication to avoid re-ingesting
unchanged documents.

### Retrieval

At each `chat()` invocation, `ResilientContentRetriever.retrieve(query)`:

```
query.text() → embeddingModel.embed()
             → pgvector ANN query: SELECT ... ORDER BY embedding <=> query_vec LIMIT 5
             → filter by cosine similarity ≥ 0.7
             → List<Content>
```

The chunks are injected into the LangChain4j prompt immediately before the user message. The model sees them
as ground-truth context and is instructed (in the system prompt) to use them without fabricating data.

### What the RAG Answers vs What Tools Answer

RAG handles **static, document-based knowledge**: procedures, policies, definitions, historical reference
material. Tools handle **live, transactional data**: current inventory, open orders, customer records. The
two complement each other — a query like "how do I handle a return for an out-of-warranty part?" might pull
a policy doc from RAG while also calling `OrderFacadeTool` to look up the order.

---

## 9. SimpleChatClassifier: The Fast-Path Gate

`SimpleChatClassifier.isSimpleChat(message)` runs before any tool selection or embedding call. It gates out
social/conversational messages to avoid an unnecessary embedding round-trip and model overhead.

**Gate conditions** (all must pass to be classified as simple chat):

1. `message.length() ≤ 160 chars` after normalization
2. `tokenCount ≤ 12 tokens` (split on non-alphanumeric)
3. One of:
   - `isPureSocialIntent()` — matches greeting, thanks, social question, capability inquiry patterns
   - No "strong task signal" — no question mark (unless it's a social question or capability), no quantity
     question pattern, no task request phrase, and no business/action/task-cue keywords in the token set

If classified as simple: `chatModel.chat(SystemMessage + UserMessage)` is called directly with no tools and
no RAG. This saves two embedding calls and skips the AiServices overhead.

**Key concern**: The 12-token ceiling is quite aggressive. A message like
"What is the stock count for part 1234?" has ~10 tokens and _might_ pass through as simple chat if none of
the keyword patterns fire. The classifier would need `stock`, `count`, or `part` in its `BUSINESS_KEYWORD`
or `TASK_CUE_KEYWORD` sets to catch it. The correctness of this gate depends heavily on the quality and
completeness of the rules in the `simple_chat_rule` table.

---

## 10. Two Separate Tool Systems

It is worth emphasizing explicitly that the codebase has two distinct tool lifecycles that do not interact:

| Dimension         | System A (MCP Protocol)          | System B (LangChain4j Agent)   |
| ----------------- | -------------------------------- | ------------------------------ |
| Discovery         | Eureka + OpenAPI spec            | DB `mcp_tool` + Spring beans   |
| Registration      | `McpAsyncServer.addTool()`       | `AiServices.builder().tools()` |
| Invocation        | MCP client → SSE transport       | LangChain4j tool-calling loop  |
| Purpose           | External MCP clients (e.g., IDE) | Internal chat agent            |
| Config            | `mcp.server.included-services`   | `handler_bean` in DB           |
| Semantic indexing | No                               | Yes (pgvector embeddings)      |

An operator adding a new microservice endpoint only to the Eureka/OpenAPI system will **not** make it
available to the internal chat agent. Both registration paths must be maintained independently.

---

## 11. Improvement Recommendations

### 11.1 Increase `candidate-tool-limit` Default

**Current**: `mcp.agent.candidate-tool-limit = 2`

With only 2 candidates, a slightly off-topic description or imprecise query can exclude the correct tool,
and the only safety net is the keyword-based fallback. The fall-through to the full role tool set only triggers
on _zero_ resolved beans — if 2 wrong tools are returned and resolve to beans, they get used.

**Recommendation**: Default to 4–5 candidates. The cost is slightly more context in the model prompt;
the benefit is significantly higher recall. The scoring formula already handles ranking, so quality doesn't
degrade linearly with topK.

### 11.2 Normalize the Scoring Formula

**Current**:

```
total = 1/(rank+1) + priority - latencyPenalty - costPenalty
```

`semanticScore` from rank 0 = 1.0, rank 9 = 0.1 — a [0.1, 1.0] range.
`priorityBoost` is a raw DB double with no defined range — could be 0–10, 0–1, or anything.
`latencyPenalty` is capped at 0.2.
`costPenalty` is 0.0–0.2.

A priority value of `2.0` would swamp any semantic signal. The formula needs all components normalized to
a common scale (e.g., all in [0, 1]) and weighted explicitly:

```
total = w_sem * semanticScore_norm
      + w_pri * normalize(priority)
      - w_lat * latencyScore
      - w_cost * costScore
```

Where `w_sem + w_pri = 1.0` and penalty weights are independent multipliers.

### 11.3 Add Semantic Tool Selection to the Streaming Path

`StreamingSessionAgentManager` bypasses `ToolRegistryService` entirely. It uses the full role tool set plus
`ExaWebSearchTool` for every query, regardless of content. This means streaming users get a wider, unfocused
tool prompt, which can reduce model accuracy and increase latency.

Apply the same `selectTools()` logic from `SessionAgentManager` to the streaming manager. Since streaming
is inherently async, the embedding call can be non-blocking.

### 11.4 Fix the Agent Cache vs. Tool Selection Interaction

**Current behavior**: Each unique combination of tool names creates a new cached `PosAssistant` instance
(keyed `role::tool1+tool2`). With topK=2 and N role tools, this could produce O(N²) agent instances per
role, each with its own `EmbeddingStoreContentRetriever` configuration.

**Better approach**: Decouple tool selection from agent instances. Cache one agent per role that accepts
the tool list as a runtime parameter, or use a dynamic tool list injection pattern. LangChain4j's
`AiServices` currently requires tools at build time, but you can build a pool of agents per tool subset
or pass the query and tool context through a single orchestrator agent.

### 11.5 Replace Keyword Fallback with Embedding-Based Fallback

`fallbackToolsForMessage()` in `SessionAgentManager` uses hardcoded regex word lists to decide whether
to add `ExaWebSearchTool`, `InventoryFacadeTool`, or `OrderFacadeTool`. This is brittle — the word `"po"`
(purchase order) is two characters and could match on many unrelated tokens.

Replace with the same ANN embedding search used for `ToolRegistryService`, with a lower score threshold
to allow broader recall for fallback. Alternatively, merge the fallback tools into the primary semantic
selection rather than running them as a separate keyword pass.

### 11.6 Strengthen `@Tool` and `@P` Descriptions

The quality of NLQ-to-tool mapping is entirely a function of how descriptive the `@Tool` annotation text
is. Currently, descriptions are brief:

```java
@Tool("Check current stock level for a product by SKU number")
```

This is adequate for single-domain queries but degrades when queries span domains. Richer descriptions
that include example phrases (synonyms, colloquial phrasing) improve embedding similarity:

```java
@Tool("Check current inventory stock level, quantity on hand, or availability for a product by its SKU, part number, or item code")
```

The `mcp_tool.description` column is what gets embedded — **updating it in the DB (and re-running
`ToolEmbeddingInitializer`) is cheaper than redeploying** and should be the primary tuning lever.

### 11.7 Surface the System Prompt's Actual Role Context

The `PosAssistant` system prompt template includes `{{roleContext}}`, which is populated with only:

```java
String roleContext = "Current user role: " + role;
```

This provides the model with no actionable information about what the role can do or should not do.
The role-specific behavior should come from the DB-stored system prompt, but if operators haven't
written distinct prompts per role, all roles get the same `default` prompt.

**Recommendation**: Seed role-specific system prompts that enumerate the domains relevant to each role.
For example, `ROLE_CASHIER` should be told it has access to sales and payment tools but not HR or admin
tools, so the model doesn't attempt to call tools it doesn't have.

### 11.8 Add Per-Request RAG Filtering

`EmbeddingStoreContentRetriever` currently retrieves the top-5 most similar chunks globally. There is no
filtering by role, date, or document category. A cashier and an admin asking the same question get the
same RAG context.

LangChain4j's `EmbeddingStoreContentRetriever` supports a `filter` on metadata. The ingestion pipeline
already sets `document_id` and `chunk_index` metadata. Add a `category` or `role_scope` metadata field
at ingestion time and apply a metadata filter at retrieval time keyed to the user's role:

```java
Filter roleFilter = metadataKey("role_scope").isIn(List.of(role, "ALL"));
EmbeddingStoreContentRetriever.builder()
    .filter(roleFilter)
    ...
```

### 11.9 Add Conversation Workflow State Tracking

The `ToolSelectionContext` accepts a `workflowState` field and the DB has a workflow state column on
`mcp_tool` (currently always `"IDLE"`). This is an unfinished capability: the intent is that mid-workflow
states (e.g., `CREATING_PO`, `PROCESSING_RETURN`) could activate a different tool set.

Persisting workflow state per session (e.g., in `NltiSession`) and driving tool selection from it would
allow the agent to constrain its tool surface to only what's relevant for the current step, reducing
hallucination risk and improving focus.

---

## 12. Ad-Hoc Database Query Access

The proposal is to grant database access based on the caller's JWT-derived role, enabling the agent to
run arbitrary or semi-structured queries against operational data.

### Approach Options

#### Option A: Text-to-SQL Tool (most flexible, highest risk)

Add a `DatabaseQueryTool` Spring bean with a method like:

```java
@Tool("Run a read-only ad-hoc query against POS business data using natural language")
public String queryData(@P("Natural language description of the data to retrieve") String question) {
    String sql = sqlGeneratorModel.generate(schemaContext, question);
    return jdbcTemplate.query(sql, resultSetExtractor);
}
```

Internally, this could be a two-pass approach:

1. A separate LLM call (or the same model) generates SQL from the question + a schema description.
2. The generated SQL is validated (parse-only, role check, no mutations), then executed against a
   **read-only** connection.

**Security requirements for this path**:

- Read-only JDBC connection (PostgreSQL `GRANT SELECT` only, separate credentials per role tier)
- Row-level security (RLS) in PostgreSQL: policies that restrict which rows each role can see
- Strict SQL validation: only `SELECT` statements; reject any DDL, DML, or subqueries referencing
  forbidden schemas
- Query timeout: hard cap (e.g., 5s) to prevent expensive full-table scans
- Row limit: `LIMIT 500` appended to all generated queries regardless of model output
- Never concatenate model-generated text into a `PreparedStatement` as a literal — parse the SQL
  and parameterize any user-supplied values

**PostgreSQL RLS example**:

```sql
CREATE POLICY cashier_orders ON orders
    FOR SELECT USING (store_id = current_setting('app.store_id')::int);
```

The JDBC session can set `app.store_id` from the JWT before executing the query.

#### Option B: Schema-Scoped Query Tools (safer, less flexible)

Instead of arbitrary SQL, define bounded query tools per domain:

```java
@Tool("Get summarized sales report for a date range")
public String getSalesReport(
    @P("Start date (YYYY-MM-DD)") String startDate,
    @P("End date (YYYY-MM-DD)") String endDate) { ... }
```

This keeps the model working with typed parameters and avoids SQL generation altogether. The tool
internally constructs a safe parameterized query. This is the recommended starting point before
enabling text-to-SQL.

#### Option C: Hybrid — Schema-Aware Text-to-SQL with Approval Gate

For admin users only: generate SQL, display it to the user for approval before execution, then execute.
This is the most auditable pattern and avoids silent errors.

### Role Mapping to Database Access

| Role                  | Recommended DB Access                                      |
| --------------------- | ---------------------------------------------------------- |
| `ROLE_ADMIN`          | All read-only schemas; RLS bypass allowed for audit tables |
| `ROLE_MANAGER`        | Store-scoped reads: orders, sales, inventory, workorders   |
| `ROLE_SERVICE_WRITER` | Vehicle, workorder, parts — own store only                 |
| `ROLE_CASHIER`        | Sales, payment, returns — own terminal/store only          |
| `ROLE_SUPPLIER`       | Catalog and PO tables — own supplier account only          |

The JWT's role (extracted by `extractPrimaryRole()`) maps to a PostgreSQL role or session variable that
PostgreSQL RLS uses to scope queries automatically.

### Model Capability Warning for Text-to-SQL

See §13 for a full assessment, but the critical point is: **llama3.1:8b is insufficient for reliable
text-to-SQL**. If you pursue Option A, use a more capable model for the SQL generation step specifically —
either a fine-tuned SQL model or a cloud API (Claude Haiku/Sonnet, GPT-4o-mini). The conversational
layer can remain on the local model.

---

## 13. Model Appraisal: llama3.1:8b

The system uses `llama3.1:8b` via Ollama for both blocking and streaming chat, at temperature 0.2.
The embedding model is `nomic-embed-text` (768 dimensions, separate Ollama instance).

### Capability Assessment by Task Type

| Task                                                 | Assessment     | Notes                                                                                                               |
| ---------------------------------------------------- | -------------- | ------------------------------------------------------------------------------------------------------------------- |
| Greetings and social chat                            | **Sufficient** | Never reaches the model; SimpleChatClassifier handles it                                                            |
| Simple factual Q&A with RAG context                  | **Adequate**   | Model is mostly summarizing retrieved text; low reasoning bar                                                       |
| Single-tool invocation with clear intent             | **Adequate**   | Works reliably when query maps cleanly to one tool                                                                  |
| Multi-tool chaining (2+ sequential calls)            | **Marginal**   | Struggles with state tracking across turns; often stops at first tool result                                        |
| Ambiguous queries requiring tool-parameter inference | **Marginal**   | May hallucinate arguments or omit required parameters                                                               |
| Complex reasoning + multiple data sources            | **Inadequate** | Context window and reasoning depth are limiting factors                                                             |
| Text-to-SQL (ad-hoc queries)                         | **Inadequate** | Produces syntactically plausible but semantically incorrect SQL, especially with JOINs, aggregations, and date math |
| Role-appropriate response filtering                  | **Marginal**   | Without strong system prompt reinforcement, will sometimes answer questions outside role scope                      |

### Observed Failure Modes

**Tool call precision**: llama3.1:8b was not trained with the same depth of function-calling instruction
tuning as OpenAI or Anthropic models. At temperature 0.2 it is reasonably stable for single calls, but
can produce:

- Tool calls with missing required parameters (model guesses or omits)
- Calls to the wrong tool when two tools have similar-sounding descriptions
- Failure to call any tool when the query is ambiguous (hallucinates an answer instead)
- Looping: sometimes calls the same tool twice with slightly different arguments

**Context window utilization**: With 50 chat messages in memory + 5 RAG chunks + tool schemas for 2-5
tools, the effective context can exceed 6k tokens per request. At 8B parameters, the model's ability to
attend to all of this simultaneously is limited. Relevant tool results from turn 3 may effectively be
"forgotten" by turn 8.

**JSON generation**: Tool argument extraction to JSON is generally reliable for simple string parameters
but degrades for structured parameters (dates in specific formats, nested objects, enums).

### Recommendations

**For current scope (single-tool POS queries)**: `llama3.1:8b` is workable with careful prompt engineering
and good `@Tool` descriptions. Expect a 10–20% failure rate in production on complex or multi-step queries.

**For improved reliability**: Upgrade to `llama3.1:70b` or `llama3.3:70b` if self-hosting on adequate
hardware. The 70B models have substantially better function-calling behavior and multi-step reasoning.
Quantized Q4 versions run on ~48GB VRAM.

**For text-to-SQL specifically**: Do not use llama3.1:8b. Options:

- `sqlcoder-7b` or `sqlcoder-34b` — fine-tuned specifically for text-to-SQL, much better schema following
- `defog/sqlcoder-70b-alpha` — highest quality self-hosted option
- Claude Haiku or Sonnet via API — commercial option with excellent SQL generation and safety controls

**For mixed-mode deployment**: The `ModelFallbackConfiguration` already provides a fallback to `mistral:7b`
(which is comparable to llama3.1:8b). A more useful fallback would be a stronger model for high-complexity
requests. One pattern: use the `SimpleChatClassifier` heuristic as a routing gate — simple chat and RAG
summarization stay on 8B; anything with 2+ tool calls or text-to-SQL routes to a larger model or API.

### Configuration Notes

```yaml
langchain4j:
  ollama:
    chat-model:
      model-name: llama3.1:8b
      temperature: 0.2 # Appropriate for structured tasks
      timeout: 180s # Very generous — suggests latency is already a concern
```

The 180s timeout is a warning sign. Llama3.1:8b at default Ollama settings can produce ~10–30 tok/s on
modern hardware for a 2k-token prompt. A 180s timeout implies either high concurrency, large context,
or inadequate hardware. The streaming path exists precisely to address this UX problem — prioritize
migrating callers to the streaming endpoint.

### nomic-embed-text Assessment

For RAG and tool similarity search, `nomic-embed-text` at 768 dimensions is a good choice. It was
specifically designed for retrieval tasks, has strong performance on the MTEB benchmark, and runs
efficiently on CPU. No change recommended here.

---

_Document last updated: 2026-05-03_
