package com.positivity.mcp.internal.orchestration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.service.ToolAuditService;
import com.positivity.mcp.service.StreamingSessionAgentCacheMetrics;
import com.positivity.mcp.service.StreamingAgentOrchestrationService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

@Component
@Profile("alpha")
public class StreamingSessionAgentManager
    implements StreamingAgentOrchestrationService, StreamingSessionAgentCacheMetrics {

  private static final Logger LOGGER = LoggerFactory.getLogger(StreamingSessionAgentManager.class);

  private final Cache<String, CachedStreamingAgent> agentCache;
  private final Cache<String, AtomicInteger> requestCountCache;
  private final StreamingChatModel streamingChatModel;
  private final EmbeddingModel embeddingModel;
  private final PgVectorEmbeddingStore embeddingStore;
  private final ToolRegistry toolRegistry;
  private final ExaWebSearchTool exaWebSearchTool;
  @Nullable
  private final ToolAuditService toolAuditService;
  private final int memoryMaxMessages;
  private final int rateLimitPerSession;

  public StreamingSessionAgentManager(
      @NonNull StreamingChatModel streamingChatModel,
      @NonNull EmbeddingModel embeddingModel,
      @NonNull PgVectorEmbeddingStore embeddingStore,
      @NonNull ToolRegistry toolRegistry,
      @NonNull ExaWebSearchTool exaWebSearchTool,
      @Nullable ToolAuditService toolAuditService,
      @Value("${mcp.agent.cache-ttl-minutes:30}") int cacheTtlMinutes,
      @Value("${mcp.agent.max-cached-agents:500}") int maxCachedAgents,
      @Value("${mcp.agent.memory-max-messages:50}") int memoryMaxMessages,
      @Value("${pos.nlti.rate-limit.per-session:100}") int rateLimitPerSession) {
    this.streamingChatModel = streamingChatModel;
    this.embeddingModel = embeddingModel;
    this.embeddingStore = embeddingStore;
    this.toolRegistry = toolRegistry;
    this.exaWebSearchTool = exaWebSearchTool;
    this.toolAuditService = toolAuditService;
    this.memoryMaxMessages = memoryMaxMessages;
    this.rateLimitPerSession = rateLimitPerSession;
    int sanitizedMaxCachedAgents = Math.max(1, maxCachedAgents);
    this.requestCountCache = Caffeine.newBuilder()
        .maximumSize(sanitizedMaxCachedAgents)
        .expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))
        .build();
    this.agentCache = Caffeine.newBuilder()
        .maximumSize(sanitizedMaxCachedAgents)
        .expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))
        .removalListener((String userId, CachedStreamingAgent ignored,
            com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
          if (userId != null) {
            requestCountCache.invalidate(userId);
          }
        })
        .build();
  }

  @Override
  public @NonNull Flux<String> streamChat(
      @NonNull String userId,
      @NonNull String role,
      @NonNull String message) {
    AtomicInteger requestCount = requestCountCache.get(userId, key -> new AtomicInteger(0));
    if (requestCount.incrementAndGet() > rateLimitPerSession) {
      requestCount.decrementAndGet();
      LOGGER.warn("Rate limit exceeded for userId: {}", userId);
      throw new RateLimitExceededException("Rate limit exceeded");
    }

    StreamingPosAssistant agent = getOrCreateAgent(userId, role);
    String roleContext = "Current user role: " + role;
    long startMs = System.currentTimeMillis();

    return Flux.<String>create(emitter -> streamTokens(agent, message, roleContext, emitter))
        .doOnComplete(() -> {
          int elapsedMs = (int) (System.currentTimeMillis() - startMs);
          if (toolAuditService != null) {
            toolAuditService.logToolExecution(null, userId, true, false, elapsedMs, null);
          }
        })
        .doOnError(exception -> {
          if (toolAuditService != null) {
            toolAuditService.logToolExecution(
                null,
                userId,
                false,
                false,
                0,
                exception.getClass().getSimpleName());
          }
        });
  }

  @Override
  public void evict(@NonNull String userId) {
    agentCache.invalidate(userId);
  }

  @Override
  public long getCacheSize() {
    return agentCache.estimatedSize();
  }

  private @NonNull StreamingPosAssistant getOrCreateAgent(
      @NonNull String userId,
      @NonNull String role) {
    CachedStreamingAgent cached = agentCache.getIfPresent(userId);
    if (cached != null && role.equals(cached.role())) {
      return cached.agent();
    }
    if (cached != null) {
      agentCache.invalidate(userId);
    }
    StreamingPosAssistant agent = buildAgent(role);
    agentCache.put(userId, new CachedStreamingAgent(role, agent));
    return agent;
  }

  private @NonNull StreamingPosAssistant buildAgent(@NonNull String role) {
    List<Object> tools = toolRegistry.resolveToolsForRole(role);
    tools.add(exaWebSearchTool);

    ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(5)
        .minScore(0.7)
        .build();

    var chatMemory = MessageWindowChatMemory.withMaxMessages(memoryMaxMessages);

    return AiServices.builder(StreamingPosAssistant.class)
        .streamingChatModel(streamingChatModel)
        .tools(tools)
        .contentRetriever(contentRetriever)
        .chatMemory(chatMemory)
        .build();
  }

  private void streamTokens(
      @NonNull StreamingPosAssistant agent,
      @NonNull String message,
      @NonNull String roleContext,
      @NonNull FluxSink<String> emitter) {
    agent.chat(message, roleContext)
        .onPartialResponse(token -> {
          if (!emitter.isCancelled()) {
            emitter.next(token);
          }
        })
        .onCompleteResponse(response -> emitter.complete())
        .onError(emitter::error)
        .start();
  }

  private record CachedStreamingAgent(@NonNull String role, @NonNull StreamingPosAssistant agent) {
  }
}
