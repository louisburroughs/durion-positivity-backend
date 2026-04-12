package com.positivity.mcp.internal.orchestration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.service.AgentOrchestrationService;
import com.positivity.mcp.service.SystemPromptService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.List;

@Component
@Profile("preprod")
public class SessionAgentManager implements AgentOrchestrationService {

  private final Cache<String, PosAssistant> agentCache;
  private final ChatModel chatModel;
  private final EmbeddingModel embeddingModel;
  private final PgVectorEmbeddingStore embeddingStore;
  private final ToolRegistry toolRegistry;
  private final ExaWebSearchTool exaWebSearchTool;
  private final InventoryFacadeTool inventoryFacadeTool;
  private final OrderFacadeTool orderFacadeTool;
  @SuppressWarnings("unused")
  private final SystemPromptService systemPromptService;
  private final int memoryMaxMessages;

  public SessionAgentManager(
      @NonNull ChatModel chatModel,
      @NonNull EmbeddingModel embeddingModel,
      @NonNull PgVectorEmbeddingStore embeddingStore,
      @NonNull ToolRegistry toolRegistry,
      @NonNull ExaWebSearchTool exaWebSearchTool,
      @NonNull InventoryFacadeTool inventoryFacadeTool,
      @NonNull OrderFacadeTool orderFacadeTool,
      @NonNull SystemPromptService systemPromptService,
      @Value("${mcp.agent.cache-ttl-minutes:30}") int cacheTtlMinutes,
      @Value("${mcp.agent.max-cached-agents:500}") int maxCachedAgents,
      @Value("${mcp.agent.memory-max-messages:50}") int memoryMaxMessages) {
    this.chatModel = chatModel;
    this.embeddingModel = embeddingModel;
    this.embeddingStore = embeddingStore;
    this.toolRegistry = toolRegistry;
    this.exaWebSearchTool = exaWebSearchTool;
    this.inventoryFacadeTool = inventoryFacadeTool;
    this.orderFacadeTool = orderFacadeTool;
    this.systemPromptService = systemPromptService;
    this.memoryMaxMessages = memoryMaxMessages;
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
    PosAssistant agent = getOrCreateAgent(userId, role);
    String roleContext = "Current user role: " + role;
    return agent.chat(message, roleContext);
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
