# LangChain4j Orchestration SPECIFICATION

## Status

ACCEPTED — replaces the earlier external-agent orchestration proposal.

## Problem Statement

The previous external-agent architecture delegated LLM orchestration to a monolithic platform. This creates three blockers for the Durion POS use case:

1. **Per-role tool isolation** — Each user role (Cashier, Manager, Admin, Supplier) needs a distinct tool set. The previous approach had no native mechanism for per-session tool filtering based on the caller's RBAC role.
2. **Session-scoped agent caching** — Expensive resources (model connections, tool registries, chat memory) should be created once per user and reused across multiple chat sessions. The previous session model did not map to this requirement.
3. **Operational simplicity** — The previous approach introduced a large operational dependency (its own Postgres, Redis, background workers, migration scripts) for functionality that LangChain4j provides as library-level primitives inside the existing Spring Boot process.

## Proposed Architecture

```text
User (frontend / API)
   │
   ▼
pos-mcp-server (Spring Boot 4.0.x, Java 25)
   │
   ├─ SessionAgentManager ─────── per-user agent cache (Caffeine, TTL)
   │   └─ resolves: userId → role → AiService instance
   │       ├─ role-specific tool set (from ToolRegistry)
   │       ├─ Exa web search tool (always included)
   │       ├─ RAG ContentRetriever (shared pgvector store)
   │       └─ per-session ChatMemory (MessageWindowChatMemory)
   │
   ├─ LangChain4j ─────────────── orchestration runtime
   │   ├─ OllamaChatModel ──────── local or network-reachable Ollama runtime
   │   ├─ OllamaEmbeddingModel ── nomic-embed-text or similar
   │   └─ AiServices proxy ────── handles tool-calling loop, memory, RAG
   │
   ├─ ToolRegistry ────────────── implements existing design doc
   │   ├─ role → tool mapping (DB-backed, Flyway-managed)
   │   ├─ workflow state gating
   │   ├─ OpenAPI discovery (existing code, unchanged)
   │   └─ embedding-based ranking (pgvector, deferred to Phase 2)
   │
   ├─ Exa WebSearchTool ───────── always-on web search capability
   │
   ├─ RAG (pgvector) ──────────── shared document store
   │   ├─ PgVectorEmbeddingStore
   │   └─ EmbeddingStoreContentRetriever
   │
   └─ Audit + Observability ──── existing NltiAuditEvent + OTel
```

### System Boundaries

| Component | Role | Deployment |
|-----------|------|------------|
| pos-mcp-server | Tool host, LLM orchestration, session management, audit | Spring Boot service (existing) |
| Ollama | Model runtime (chat + embeddings) | Local container or reachable internal endpoint |
| PostgreSQL | Tool registry, RAG embeddings, audit, chat memory | Existing shared instance |
| Exa | Web search API | External SaaS |

### What The Previous External Platform Handled → What Replaces It

| Previous Responsibility | LangChain4j Replacement |
|-------------------------|------------------------|
| Prompt construction | `AiServices` interface with system message provider |
| Tool-use planning | LangChain4j tool-calling protocol (model-driven) |
| Conversation memory | `MessageWindowChatMemory` (token-limited, per-session) |
| Retrieval / RAG | `EmbeddingStoreContentRetriever` + pgvector |
| Model abstraction | `OllamaChatModel` / `OllamaStreamingChatModel` |
| User-facing interface | Stays in frontend; `pos-mcp-server` is the API backend |

---

## What Survives from Current Codebase

| Asset | Location | Reuse |
|-------|----------|-------|
| OpenAPI tool discovery | `internal/discovery/` | **Keep** — continues as the tool source-of-truth |
| Tool registry design | [tool-registry-implementation.md](tool-registry-implementation.md) | **Implement** — becomes per-role tool resolver |
| Facade tool catalog | [domain-facade-tools.md](domain-facade-tools.md) | **Keep** — defines the 16 curated facade tools |
| `McpPermissions` + Spring Security | `internal/security/` | **Keep** — enforces RBAC at tool execution boundary |
| `NltiSession` / `NltiRequest` / audit entities | `internal/entity/` | **Keep** — session tracking and audit trail |
| `SystemPrompt` + `LlmApiConfig` CRUD | `internal/entity/` + controllers | **Keep** — prompt and model config management |
| SSE transport + `McpAsyncServer` | `internal/config/McpServerConfiguration` | **Keep** — external MCP clients can still connect |
| `LlmApiProperties` | `internal/config/` | **Evolve** — extend for Ollama-specific settings |
| Local backend compose stack | `docker-compose.yml` | **Keep** |
| `internal/llm/` (empty) | — | **Replace** with LangChain4j orchestration |
| `internal/orchestration/` (empty) | — | **Replace** with SessionAgentManager |

---

## Dependencies (pom.xml additions)

```xml
<!-- LangChain4j core + Ollama integration (Spring Boot 4 starter) -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-ollama-spring-boot4-starter</artifactId>
    <version>${langchain4j.spring.version}</version>
</dependency>

<!-- pgvector embedding store for RAG -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-pgvector</artifactId>
    <version>${langchain4j.version}</version>
</dependency>

<!-- LangChain4j Spring Boot 4 starter (AiServices, memory, tools) -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-spring-boot4-starter</artifactId>
    <version>${langchain4j.spring.version}</version>
</dependency>

<!-- Caffeine for per-user agent caching -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

Property in parent or module POM:

```xml
<properties>
    <!-- Core LangChain4j library (pgvector, etc.) -->
    <langchain4j.version>1.13.0</langchain4j.version>
    <!-- Spring Boot 4 integration starters (separate versioning from core) -->
    <langchain4j.spring.version>1.13.0-beta23</langchain4j.spring.version>
</properties>
```

---

## Configuration (application.yml additions)

```yaml
# Ollama connection
langchain4j:
  ollama:
    chat-model:
      base-url: ${OLLAMA_BASE_URL}
      model-name: ${OLLAMA_CHAT_MODEL:llama3.1:8b}
      temperature: 0.2
      timeout: 60s
    embedding-model:
      base-url: ${OLLAMA_BASE_URL}
      model-name: ${OLLAMA_EMBEDDING_MODEL:nomic-embed-text}
      timeout: 30s

# RAG pgvector store
mcp:
  rag:
    table-name: mcp_document_embedding
    dimension: 768         # nomic-embed-text dimension
    index-type: ivfflat    # or hnsw
  agent:
    cache-ttl-minutes: 30  # per-user agent cache TTL
    max-cached-agents: 500
    memory-max-messages: 50

# Exa web search
exa:
  api-key: ${EXA_API_KEY}
  base-url: https://api.exa.ai
  search-type: auto
  max-results: 5
```

---

## Code: Core Components

### 1. AiServices Interface (the agent contract)

Each user session gets a dynamically built `AiServices` proxy. This is the interface LangChain4j implements at runtime with tool calling, memory, and RAG:

```java
package com.positivity.mcp.internal.orchestration;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface PosAssistant {

    @SystemMessage("""
        You are a POS assistant for Durion Positivity.
        Use the provided tools to answer questions about inventory, orders,
        customers, pricing, and other POS operations.
        Always verify information using tools before answering.
        If you cannot find the answer, say so clearly.
        Never fabricate data.
        {{roleContext}}
        """)
    String chat(@UserMessage String userMessage, @V("roleContext") String roleContext);
}
```

### 2. SessionAgentManager (per-user agent caching)

```java
package com.positivity.mcp.internal.orchestration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class SessionAgentManager {

    private final Cache<String, PosAssistant> agentCache;
    private final ChatLanguageModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final PgVectorEmbeddingStore embeddingStore;
    private final ToolRegistry toolRegistry;
    private final ExaWebSearchTool exaWebSearchTool;
    private final SystemPromptService systemPromptService;

    public SessionAgentManager(
            @NonNull ChatLanguageModel chatModel,
            @NonNull EmbeddingModel embeddingModel,
            @NonNull PgVectorEmbeddingStore embeddingStore,
            @NonNull ToolRegistry toolRegistry,
            @NonNull ExaWebSearchTool exaWebSearchTool,
            @NonNull SystemPromptService systemPromptService,
            @Value("${mcp.agent.cache-ttl-minutes:30}") int cacheTtlMinutes,
            @Value("${mcp.agent.max-cached-agents:500}") int maxCachedAgents) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.toolRegistry = toolRegistry;
        this.exaWebSearchTool = exaWebSearchTool;
        this.systemPromptService = systemPromptService;
        this.agentCache = Caffeine.newBuilder()
                .maximumSize(maxCachedAgents)
                .expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))
                .build();
    }

    /**
     * Returns a cached agent for the user, creating one if absent.
     * The agent is role-aware: its tool set is determined by the user's role.
     * Multiple chat sessions for the same user reuse this agent instance.
     */
    public @NonNull PosAssistant getOrCreateAgent(
            @NonNull String userId,
            @NonNull String role) {
        return agentCache.get(userId, key -> buildAgent(role));
    }

    private PosAssistant buildAgent(@NonNull String role) {
        // 1. Resolve role-specific tools
        List<Object> tools = toolRegistry.resolveToolsForRole(role);

        // 2. Always include Exa web search
        tools.add(exaWebSearchTool);

        // 3. Build RAG content retriever
        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.7)
                .build();

        // 4. Build per-session chat memory
        var chatMemory = MessageWindowChatMemory.withMaxMessages(50);

        // 5. Assemble AiServices proxy
        return AiServices.builder(PosAssistant.class)
                .chatLanguageModel(chatModel)
                .tools(tools)
                .contentRetriever(contentRetriever)
                .chatMemory(chatMemory)
                .build();
    }

    /**
     * Evicts a user's cached agent. Call when role changes or on explicit logout.
     */
    public void evict(@NonNull String userId) {
        agentCache.invalidate(userId);
    }
}
```

### 3. ToolRegistry (role-based tool resolution)

This bridges the existing OpenAPI discovery with per-role filtering. The facade tools from [domain-facade-tools.md](domain-facade-tools.md) are the canonical tool set.

```java
package com.positivity.mcp.internal.orchestration;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    private final Map<String, List<Object>> roleToolMap;

    /**
     * Constructed at startup from DB-backed role-tool mappings.
     * Each tool is a Java object with @Tool-annotated methods.
     */
    public ToolRegistry(@NonNull ToolRegistryLoader loader) {
        this.roleToolMap = loader.loadRoleToolMappings();
    }

    /**
     * Returns the tool instances visible to the given role.
     * Returns a mutable copy so callers can append session-specific tools.
     */
    public @NonNull List<Object> resolveToolsForRole(@NonNull String role) {
        List<Object> tools = roleToolMap.getOrDefault(role, List.of());
        return new ArrayList<>(tools);
    }
}
```

### 4. Facade Tool Example (LangChain4j @Tool annotation)

Each facade tool wraps backend service calls. These replace the raw OpenAPI proxy for LLM-facing interactions:

```java
package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class InventoryFacadeTool {

    private final RestClient restClient;

    public InventoryFacadeTool(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl("http://pos-inventory/v1/inventory")
                .build();
    }

    @Tool("Check current stock level for a product by SKU number")
    public String checkStock(
            @P("The SKU number to look up") @NonNull String sku) {
        return restClient.get()
                .uri("/stock/{sku}", sku)
                .retrieve()
                .body(String.class);
    }

    @Tool("Search inventory by product name or partial SKU")
    public String searchInventory(
            @P("Search term: product name or partial SKU") @NonNull String query) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search")
                        .queryParam("q", query)
                        .build())
                .retrieve()
                .body(String.class);
    }

    @Tool("Get stock levels for all products at a specific store location")
    public String getLocationStock(
            @P("Store location ID") @NonNull String locationId) {
        return restClient.get()
                .uri("/locations/{locationId}/stock", locationId)
                .retrieve()
                .body(String.class);
    }
}
```

```java
package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OrderFacadeTool {

    private final RestClient restClient;

    public OrderFacadeTool(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl("http://pos-order/v1/orders")
                .build();
    }

    @Tool("Look up an order by order ID")
    public String getOrder(
            @P("The order ID") @NonNull String orderId) {
        return restClient.get()
                .uri("/{orderId}", orderId)
                .retrieve()
                .body(String.class);
    }

    @Tool("Search orders by customer name, date range, or status")
    public String searchOrders(
            @P("Search query: customer name, date, or status") @NonNull String query) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search")
                        .queryParam("q", query)
                        .build())
                .retrieve()
                .body(String.class);
    }
}
```

### 5. Exa Web Search Tool

```java
package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class ExaWebSearchTool {

    private final RestClient restClient;

    public ExaWebSearchTool(
            RestClient.Builder restClientBuilder,
            @Value("${exa.base-url:https://api.exa.ai}") String baseUrl,
            @Value("${exa.api-key}") String apiKey) {
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Tool("Search the web for current information about automotive parts, " +
          "industry news, product specifications, or general knowledge")
    public String webSearch(
            @P("The search query") @NonNull String query) {
        var body = Map.of(
                "query", query,
                "type", "auto",
                "numResults", 5,
                "text", Map.of("maxCharacters", 8000)
        );

        return restClient.post()
                .uri("/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }
}
```

### 6. RAG Configuration (pgvector)

```java
package com.positivity.mcp.internal.config;

import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class RagConfiguration {

    @Bean
    public PgVectorEmbeddingStore embeddingStore(
            @NonNull DataSource dataSource,
            @Value("${mcp.rag.table-name:mcp_document_embedding}") String tableName,
            @Value("${mcp.rag.dimension:768}") int dimension) {
        return PgVectorEmbeddingStore.builder()
                .dataSource(dataSource)
                .table(tableName)
                .dimension(dimension)
                .createTable(true)   // auto-create on first run
                .build();
    }
}
```

### 7. Updated Chat Controller (routes through LangChain4j)

The existing `McpChatController` evolves to route user messages through the LangChain4j agent instead of raw `McpSyncClient`:

```java
package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.orchestration.PosAssistant;
import com.positivity.mcp.internal.orchestration.SessionAgentManager;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chat endpoint that routes user messages through a per-user LangChain4j agent.
 * The agent has role-specific tools, Exa web search, and RAG.
 */
@RestController
@RequestMapping("/v1/mcp")
public class McpChatController {

    private static final Logger logger = LoggerFactory.getLogger(McpChatController.class);

    private final SessionAgentManager sessionAgentManager;

    public McpChatController(@NonNull SessionAgentManager sessionAgentManager) {
        this.sessionAgentManager = sessionAgentManager;
    }

    @PostMapping("/chat")
    @PreAuthorize("isAuthenticated()")
    @EmitEvent(id = "MCP_CHAT_EXECUTE", apiVersion = "1")
    public ResponseEntity<ChatResponse> chat(
            @RequestBody @NonNull ChatRequest request,
            @NonNull Authentication authentication) {

        String userId = authentication.getName();
        String role = extractPrimaryRole(authentication);

        try {
            PosAssistant agent = sessionAgentManager.getOrCreateAgent(userId, role);
            String roleContext = "Current user role: " + role;
            String response = agent.chat(request.message(), roleContext);
            return ResponseEntity.ok(new ChatResponse(response));
        } catch (Exception ex) {
            logger.error("Chat failed for user '{}': {}", userId, ex.getMessage(), ex);
            return ResponseEntity.internalServerError()
                    .body(new ChatResponse("I encountered an error processing your request."));
        }
    }

    private String extractPrimaryRole(@NonNull Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .findFirst()
                .orElse("ROLE_USER");
    }

    public record ChatRequest(@NonNull String message) {}
    public record ChatResponse(@NonNull String response) {}
}
```

---

## Flyway Migration: RAG + pgvector

```sql
-- V2__pgvector_and_rag.sql

-- Enable pgvector extension (requires superuser or pre-installed extension)
CREATE EXTENSION IF NOT EXISTS vector;

-- RAG document embedding store
CREATE TABLE mcp_document_embedding (
    embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    embedding    VECTOR(768),       -- nomic-embed-text dimension
    text         TEXT NOT NULL,
    metadata     JSONB DEFAULT '{}',
    created_at   TIMESTAMP DEFAULT now()
);

CREATE INDEX mcp_doc_embedding_idx
    ON mcp_document_embedding
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
```

---

## Role → Tool Mapping (Reference Table)

Based on the 16 facade tools from [domain-facade-tools.md](domain-facade-tools.md):

| Role | Tools |
|------|-------|
| Cashier | OrderTool, CustomerTool, PricingTool, InventoryTool (read-only) |
| Service Writer | WorkorderTool, CustomerTool, VehicleTool, CatalogTool, PricingTool, InventoryTool |
| Manager | All of Service Writer + ReportingTool, AccountingTool, InvoiceTool, HRTool |
| Admin | All tools |
| Supplier | OrderTool (PO view), InventoryTool (ASN), CatalogTool (read-only) |

All roles also get: **ExaWebSearchTool** (always) + **RAG retriever** (always).

---

## Docker Compose Changes

Use the primary backend compose file in `docker-compose.yml`:

```yaml
services:
  pos-mcp-server:
    build:
      context: ./pos-mcp-server
      dockerfile: Dockerfile
    container_name: pos-mcp-server
    ports:
      - "8094:8086"
    environment:
      SPRING_PROFILES_ACTIVE: alpha
      POS_MCP_DB_HOST: postgres
      POS_MCP_DB_PORT: 5432
      POS_MCP_DB_NAME: pos_mcp
      POS_MCP_DB_USER: ${SPRING_DATASOURCE_USERNAME}
      POS_MCP_DB_PASSWORD: ${SPRING_DATASOURCE_PASSWORD}
      EUREKA_SERVER_URL: http://eureka-server:8761/eureka/
      OLLAMA_BASE_URL: http://ollama:11434
      OLLAMA_CHAT_MODEL: ${OLLAMA_CHAT_MODEL:-llama3.1:8b}
      OLLAMA_EMBEDDING_MODEL: ${OLLAMA_EMBEDDING_MODEL:-nomic-embed-text}
      EXA_API_KEY: ${EXA_API_KEY:-}
      OTEL_EXPORTER_OTLP_ENDPOINT: ${OTEL_EXPORTER_OTLP_ENDPOINT:-http://otel-collector:4318}
    depends_on:
      postgres:
        condition: service_healthy
      eureka-server:
        condition: service_healthy
    networks:
      - pos-network
```

`pos-mcp-server` and `ollama` now run directly inside the primary local backend stack.

---

## Request Flow

```text
1. User sends: POST /v1/mcp/chat { "message": "What tire stock do we have in Charlotte?" }
2. McpChatController authenticates, extracts userId + role from JWT
3. SessionAgentManager.getOrCreateAgent(userId, role)
   a. Cache hit → return existing PosAssistant
   b. Cache miss →
      i.   ToolRegistry.resolveToolsForRole("SERVICE_WRITER")
           → [WorkorderTool, CustomerTool, VehicleTool, CatalogTool,
              PricingTool, InventoryTool]
      ii.  Append ExaWebSearchTool
      iii. Build ContentRetriever (pgvector-backed)
      iv.  Build MessageWindowChatMemory(50)
      v.   AiServices.builder(PosAssistant.class).build()
      vi.  Cache under userId
4. agent.chat(message, roleContext) →
   a. LangChain4j prepends system message with role context
   b. RAG retriever fetches relevant docs from pgvector
   c. Ollama receives: system prompt + RAG context + chat history + user message + tool definitions
   d. Ollama responds with tool call: InventoryTool.getLocationStock("charlotte-01")
   e. LangChain4j executes tool call → RestClient → pos-inventory service
   f. Tool result sent back to Ollama for synthesis
   g. Ollama generates final answer
5. Response returned: { "response": "Charlotte store has 47 tires in stock: ..." }
6. NltiAuditEvent logged with tool calls, timings, and outcome
```

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Ollama tool-calling quality with small models | Hallucinated tool calls, wrong parameters | Test with ≥32B parameter models for production; use `qwen2.5:32b` or `llama3.1:70b`; keep facade tool count ≤25 per role |
| Context window overflow from long sessions | Truncated history, lost context | `MessageWindowChatMemory(50)` caps history; add summarization strategy for long sessions later |
| pgvector cold start (no documents) | RAG returns nothing, model relies solely on tools | Acceptable in Phase 1; seed with product catalogs, SOPs, knowledge base in Phase 2 |
| Caffeine cache memory pressure | Too many cached agents consume heap | Cap at 500 agents, 30-min TTL; monitor with Micrometer gauge |
| Exa API key in environment | Key rotation requires restart | Use Spring Cloud Config or Vault for dynamic secret rotation later |
| Ollama connectivity issues | Chat and embedding calls fail | Require a reachable `OLLAMA_BASE_URL`; add startup health checks for model endpoint reachability and alerting on failures |

---

## Implementation Phases

### Phase 1 — Core Orchestration (MVP)

- [x] Add LangChain4j dependencies to `pom.xml`
- [x] Create `PosAssistant` interface
- [x] Create `SessionAgentManager` with Caffeine cache
- [x] Create `ExaWebSearchTool`
- [x] Create 2–3 facade tools (Inventory, Order, Customer) with `@Tool`
- [x] Create `RagConfiguration` with pgvector
- [x] Add Flyway migration for pgvector extension
- [x] Update `McpChatController` to route through `SessionAgentManager`
- [x] Add Ollama config properties to `application.yml`
- [x] Fold the local MCP/Ollama services into the primary `docker-compose.yml` stack
- [x] Verify end-to-end: user message → tool call → response

### Phase 2 — Tool Registry + Role Gating

- [ ] Implement `ToolRegistry` with DB-backed role→tool mappings
- [ ] Add Flyway migrations for `mcp_tool`, `mcp_role`, `mcp_tool_role` tables
- [ ] Implement `ToolRegistryLoader` that reads mappings and instantiates tool beans
- [ ] Add workflow state gating (per existing design doc)
- [ ] Add admin API for tool-role mapping CRUD
- [ ] Build remaining facade tools (all 16 from domain-facade-tools.md)

### Phase 3 — Embedding-Based Tool Selection

- [ ] Add pgvector embedding column to `mcp_tool` table
- [ ] Implement embedding-based top-K tool ranking
- [ ] Integrate `ToolScorer` deterministic formula
- [ ] Add intent classification layer (optional)

### Phase 4 — RAG Content + Observability

- [ ] Seed pgvector RAG store with product catalogs, SOPs
- [ ] Add document ingestion API/CLI
- [ ] Implement `ToolAuditService` logging tool selection and execution
- [ ] Add Micrometer metrics for agent cache, tool latency, model latency
- [ ] Add adaptive priority tuning (per tool-registry-plan.md Phase 7)

### Phase 5 — Production Hardening

- [ ] Streaming responses (`OllamaStreamingChatModel` + SSE to frontend)
- [ ] Chat memory summarization for long sessions
- [ ] Rate limiting per user
- [ ] Model fallback chain (primary → secondary model)
- [ ] Secret rotation (Exa key and any protected model-endpoint credentials)
- [ ] Load testing with concurrent users

---

## Documents Superseded

| Document | Disposition |
|----------|-------------|
| [tool-registry-implementation.md](tool-registry-implementation.md) | **Still valid** — implementation patterns carry forward into `ToolRegistry` |
| [tool-registry-plan.md](tool-registry-plan.md) | **Still valid** — phased plan aligns with Phases 2–4 above |
| [domain-facade-tools.md](domain-facade-tools.md) | **Still valid** — facade tool catalog is the source of truth for tool definitions |
