package com.positivity.mcp.internal.orchestration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.service.ToolAuditService;
import com.positivity.mcp.service.AgentOrchestrationService;
import com.positivity.mcp.service.SessionAgentCacheMetrics;
import com.positivity.mcp.service.SystemPromptService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile("alpha")
public class SessionAgentManager implements AgentOrchestrationService, SessionAgentCacheMetrics {

  private static final Logger LOGGER = LoggerFactory.getLogger(SessionAgentManager.class);

  private final Cache<String, PosAssistant> agentCache;
  private final Cache<String, AtomicInteger> requestCountCache;
  private final ChatModel chatModel;
  private final EmbeddingModel embeddingModel;
  private final PgVectorEmbeddingStore embeddingStore;
  private final ToolRegistry toolRegistry;
  private final ExaWebSearchTool exaWebSearchTool;
  private final InventoryFacadeTool inventoryFacadeTool;
  private final OrderFacadeTool orderFacadeTool;
  @Nullable
  private final ToolAuditService toolAuditService;
  @SuppressWarnings("unused")
  private final SystemPromptService systemPromptService;
  private final int memoryMaxMessages;
  private final int rateLimitPerSession;

  public SessionAgentManager(
      @NonNull ChatModel chatModel,
      @NonNull EmbeddingModel embeddingModel,
      @NonNull PgVectorEmbeddingStore embeddingStore,
      @NonNull ToolRegistry toolRegistry,
      @NonNull ExaWebSearchTool exaWebSearchTool,
      @NonNull InventoryFacadeTool inventoryFacadeTool,
      @NonNull OrderFacadeTool orderFacadeTool,
      @Nullable ToolAuditService toolAuditService,
      @NonNull SystemPromptService systemPromptService,
      @Value("${mcp.agent.cache-ttl-minutes:30}") int cacheTtlMinutes,
      @Value("${mcp.agent.max-cached-agents:500}") int maxCachedAgents,
      @Value("${mcp.agent.memory-max-messages:50}") int memoryMaxMessages,
      @Value("${pos.nlti.rate-limit.per-session:100}") int rateLimitPerSession) {
    this.chatModel = chatModel;
    this.embeddingModel = embeddingModel;
    this.embeddingStore = embeddingStore;
    this.toolRegistry = toolRegistry;
    this.exaWebSearchTool = exaWebSearchTool;
    this.inventoryFacadeTool = inventoryFacadeTool;
    this.orderFacadeTool = orderFacadeTool;
    this.toolAuditService = toolAuditService;
    this.systemPromptService = systemPromptService;
    this.memoryMaxMessages = memoryMaxMessages;
    this.rateLimitPerSession = rateLimitPerSession;
    this.requestCountCache = Caffeine.newBuilder()
        .maximumSize(Math.max(1, maxCachedAgents))
        .expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))
        .build();
    this.agentCache = Caffeine.newBuilder()
        .maximumSize(maxCachedAgents)
        .expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))
        .removalListener(
            (String userId, PosAssistant ignored, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
              if (userId != null) {
                requestCountCache.invalidate(userId);
              }
            })
        .build();
  }

  /**
   * Returns a cached agent for the user, creating one if absent.
   * The agent is role-aware: its tool set is determined by the user's role.
   * Multiple chat sessions for the same user reuse this agent instance.
   */
  @NonNull
  PosAssistant getOrCreateAgent(
      @NonNull String userId,
      @NonNull String role) {
    return agentCache.get(userId, key -> buildAgent(role));
  }

  @Override
  public @NonNull String chat(
      @NonNull String userId,
      @NonNull String role,
      @NonNull String message) {
    AtomicInteger requestCount = requestCountCache.get(userId, key -> new AtomicInteger(0));
    if (requestCount.incrementAndGet() > rateLimitPerSession) {
      requestCount.decrementAndGet();
      LOGGER.warn("Rate limit exceeded for userId: {}", userId);
      throw new RateLimitExceededException("Rate limit exceeded");
    }

    long startMs = System.currentTimeMillis();
    try {
      PosAssistant agent = getOrCreateAgent(userId, role);
      String roleContext = "Current user role: " + role;
      String response = agent.chat(message, roleContext);
      int elapsedMs = (int) (System.currentTimeMillis() - startMs);
      if (toolAuditService != null) {
        toolAuditService.logToolExecution(null, userId, true, false, elapsedMs, null);
      }
      return response;
    } catch (RuntimeException exception) {
      if (toolAuditService != null) {
        toolAuditService.logToolExecution(
            null,
            userId,
            false,
            false,
            0,
            exception.getClass().getSimpleName());
      }
      throw exception;
    }
  }

  @Override
  public long getCacheSize() {
    return agentCache.estimatedSize();
  }

  private PosAssistant buildAgent(@NonNull String role) {
    // 1. Resolve role-specific tools
    List<Object> tools = toolRegistry.resolveToolsForRole(role);

    // 2. Always include Exa web search and Phase 1 facade tools
    tools.add(exaWebSearchTool);
    tools.add(inventoryFacadeTool);
    tools.add(orderFacadeTool);

    // 3. Build RAG content retriever
    ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(5)
        .minScore(0.7)
        .build();

    // 4. Build per-session chat memory
    var chatMemory = MessageWindowChatMemory.withMaxMessages(memoryMaxMessages);

    // 5. Assemble AiServices proxy
    return AiServices.builder(PosAssistant.class)
        .chatModel(chatModel)
        .tools(tools)
        .contentRetriever(contentRetriever)
        .chatMemory(chatMemory)
        .build();
  }

  /**
   * Evicts a user's cached agent. Call when role changes or on explicit logout.
   */
  @Override
  public void evict(@NonNull String userId) {
    agentCache.invalidate(userId);
  }
}
