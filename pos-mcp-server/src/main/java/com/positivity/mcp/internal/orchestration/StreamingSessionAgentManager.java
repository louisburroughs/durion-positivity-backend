package com.positivity.mcp.internal.orchestration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.positivity.mcp.internal.classification.SimpleChatRuleCatalog;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.service.ToolAuditService;
import com.positivity.mcp.service.RolePromptResolver;
import com.positivity.mcp.service.StreamingAgentOrchestrationService;
import com.positivity.mcp.service.StreamingSessionAgentCacheMetrics;
import dev.langchain4j.memory.ChatMemory;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

@Component
@Profile("alpha")
public class StreamingSessionAgentManager
        implements StreamingAgentOrchestrationService, StreamingSessionAgentCacheMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(StreamingSessionAgentManager.class);
    private static final String MEMORY_KEY_SEPARATOR = "::";
    private static final int MAX_LOG_PREVIEW_LENGTH = 160;

    private final Cache<String, StreamingPosAssistant> roleAgentCache;
    private final Cache<String, ChatMemory> chatMemoryCache;
    private final Cache<String, AtomicInteger> requestCountCache;
    private final StreamingChatModel streamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final PgVectorEmbeddingStore embeddingStore;
    private final ToolRegistry toolRegistry;
    private final ExaWebSearchTool exaWebSearchTool;
    private final RolePromptResolver rolePromptResolver;

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
            @NonNull RolePromptResolver rolePromptResolver,
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
        this.rolePromptResolver = rolePromptResolver;
        this.toolAuditService = toolAuditService;
        this.memoryMaxMessages = memoryMaxMessages;
        this.rateLimitPerSession = rateLimitPerSession;
        int sanitizedMaxCachedAgents = Math.max(1, maxCachedAgents);
        this.requestCountCache = Caffeine.newBuilder()
                .maximumSize(sanitizedMaxCachedAgents)
                .expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))
                .build();
        this.chatMemoryCache = Caffeine.newBuilder()
                .maximumSize(sanitizedMaxCachedAgents)
                .expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))
                .build();
        this.roleAgentCache = Caffeine.newBuilder().maximumSize(sanitizedMaxCachedAgents).build();
        prebuildRoleAgents();
    }

    @Override
    public @NonNull Flux<String> streamChat(@NonNull String userId, @NonNull String role, @NonNull String message) {
        AtomicInteger requestCount = requestCountCache.get(userId, key -> new AtomicInteger(0));
        if (requestCount.incrementAndGet() > rateLimitPerSession) {
            requestCount.decrementAndGet();
            LOGGER.warn("Rate limit exceeded for userId: {}", userId);
            throw new RateLimitExceededException("Rate limit exceeded");
        }

        StreamingPosAssistant agent = getOrCreateAgent(userId, role);
        String roleContext = "Current user role: " + role;
        long startMs = System.currentTimeMillis();
        String memoryId = memoryKey(userId, role);
        String messagePreview = preview(message);
        LOGGER.debug(
                "MCP streaming chat dispatch userId={} role={} chars={} tokens={} preview=\"{}\"",
                userId,
                role,
                message.length(),
                tokenCount(message),
                messagePreview);
        return Flux.<String>create(emitter -> streamTokens(agent, memoryId, message, roleContext, emitter))
                .doOnComplete(() -> {
                    int elapsedMs = (int) (System.currentTimeMillis() - startMs);
                    LOGGER.debug(
                            "MCP streaming chat completed userId={} role={} totalElapsedMs={} preview=\"{}\"",
                            userId,
                            role,
                            elapsedMs,
                            messagePreview);
                    if (toolAuditService != null) {
                        toolAuditService.logToolExecution(null, userId, true, false, elapsedMs, null);
                    }
                })
                .doOnError(exception -> {
                    LOGGER.warn(
                            "MCP streaming chat failed userId={} role={} preview=\"{}\" error={}",
                            userId,
                            role,
                            messagePreview,
                            exception.getClass().getSimpleName());
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
        chatMemoryCache.asMap().keySet().removeIf(key -> key.startsWith(userId + MEMORY_KEY_SEPARATOR));
        requestCountCache.invalidate(userId);
    }

    @Override
    public long getCacheSize() {
        return roleAgentCache.estimatedSize();
    }

    private @NonNull StreamingPosAssistant getOrCreateAgent(@NonNull String userId, @NonNull String role) {
        return roleAgentCache.get(role, this::buildAgent);
    }

    private @NonNull StreamingPosAssistant buildAgent(@NonNull String role) {
        long startNanos = System.nanoTime();
        List<Object> tools = ToolSelectionSupport.mergeWithoutDuplicateToolNames(
                toolRegistry.resolveToolsForRole(role), exaWebSearchTool);

        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.7)
                .build();
        ContentRetriever resilientContentRetriever = new ResilientContentRetriever(contentRetriever,
                "embedding-store-content-retriever");

        StreamingPosAssistant agent = AiServices.builder(StreamingPosAssistant.class)
                .streamingChatModel(streamingChatModel)
                .tools(tools)
                .contentRetriever(resilientContentRetriever)
                .systemMessageProvider(memoryId -> rolePromptResolver.resolvePrompt(role))
                .chatMemoryProvider(this::chatMemoryFor)
                .build();
        LOGGER.debug("Built MCP streaming role agent role={} toolNames={}", role, toolNames(tools));
        LOGGER.info(
                "Built MCP streaming role agent role={} tools={} in {} ms", role, tools.size(), elapsedMs(startNanos));
        return agent;
    }

    private void streamTokens(
            @NonNull StreamingPosAssistant agent,
            @NonNull String memoryId,
            @NonNull String message,
            @NonNull String roleContext,
            @NonNull FluxSink<String> emitter) {
        agent.chat(memoryId, message, roleContext)
                .onPartialResponse(token -> {
                    if (!emitter.isCancelled()) {
                        emitter.next(token);
                    }
                })
                .onCompleteResponse(response -> emitter.complete())
                .onError(emitter::error)
                .start();
    }

    private void prebuildRoleAgents() {
        long startNanos = System.nanoTime();
        int prebuilt = 0;
        for (String role : toolRegistry.preloadableRoles()) {
            try {
                roleAgentCache.put(role, buildAgent(role));
                prebuilt++;
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to prebuild MCP streaming role agent role={}", role, exception);
            }
        }
        LOGGER.info("Prebuilt {} MCP streaming role agents in {} ms", prebuilt, elapsedMs(startNanos));
    }

    private @NonNull ChatMemory chatMemoryFor(@NonNull Object memoryId) {
        return chatMemoryCache.get(
                String.valueOf(memoryId), ignored -> MessageWindowChatMemory.withMaxMessages(memoryMaxMessages));
    }

    private static @NonNull String memoryKey(@NonNull String userId, @NonNull String role) {
        return userId + MEMORY_KEY_SEPARATOR + role;
    }

    private static @NonNull String preview(@NonNull String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_LOG_PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_LOG_PREVIEW_LENGTH - 3) + "...";
    }

    private static int tokenCount(@NonNull String text) {
        return SimpleChatRuleCatalog.tokenize(SimpleChatRuleCatalog.normalize(text)).size();
    }

    private static @NonNull List<String> toolNames(@NonNull List<Object> tools) {
        return tools.stream().map(tool -> tool.getClass().getSimpleName()).toList();
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
