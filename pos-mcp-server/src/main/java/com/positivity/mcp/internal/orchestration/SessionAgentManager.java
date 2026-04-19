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
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
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

@Component
@Profile("alpha")
public class SessionAgentManager implements AgentOrchestrationService, SessionAgentCacheMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionAgentManager.class);
    private static final String MEMORY_KEY_SEPARATOR = "::";

    private final Cache<String, PosAssistant> roleAgentCache;
    private final Cache<String, ChatMemory> chatMemoryCache;
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
        this.chatMemoryCache = Caffeine.newBuilder()
                .maximumSize(Math.max(1, maxCachedAgents))
                .expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))
                .build();
        this.roleAgentCache =
                Caffeine.newBuilder().maximumSize(Math.max(1, maxCachedAgents)).build();
        prebuildRoleAgents();
    }

    /**
     * Returns a cached role agent, creating one if absent. User-specific
     * conversation state is resolved later by the chat memory provider.
     */
    @NonNull
    PosAssistant getOrCreateAgent(@NonNull String userId, @NonNull String role) {
        return roleAgentCache.get(role, this::buildAgent);
    }

    @Override
    public @NonNull String chat(@NonNull String userId, @NonNull String role, @NonNull String message) {
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
            String response = agent.chat(memoryKey(userId, role), message, roleContext);
            int elapsedMs = (int) (System.currentTimeMillis() - startMs);
            if (toolAuditService != null) {
                toolAuditService.logToolExecution(null, userId, true, false, elapsedMs, null);
            }
            return response;
        } catch (RuntimeException exception) {
            if (toolAuditService != null) {
                toolAuditService.logToolExecution(
                        null, userId, false, false, 0, exception.getClass().getSimpleName());
            }
            throw exception;
        }
    }

    @Override
    public long getCacheSize() {
        return roleAgentCache.estimatedSize();
    }

    private PosAssistant buildAgent(@NonNull String role) {
        long startNanos = System.nanoTime();
        // 1. Resolve role-specific tools and append fallback tools without
        // registering duplicate @Tool method names.
        List<Object> tools = ToolSelectionSupport.mergeWithoutDuplicateToolNames(
                toolRegistry.resolveToolsForRole(role), exaWebSearchTool, inventoryFacadeTool, orderFacadeTool);

        // 2. Build RAG content retriever
        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.7)
                .build();
        ContentRetriever resilientContentRetriever =
                new ResilientContentRetriever(contentRetriever, "embedding-store-content-retriever");

        // 3. Assemble AiServices proxy. Chat memory remains per user+role through
        // the provider, so role-level agent prebuilds do not share conversations.
        PosAssistant agent = AiServices.builder(PosAssistant.class)
                .chatModel(chatModel)
                .tools(tools)
                .contentRetriever(resilientContentRetriever)
                .chatMemoryProvider(this::chatMemoryFor)
                .build();
        LOGGER.info("Built MCP role agent role={} tools={} in {} ms", role, tools.size(), elapsedMs(startNanos));
        return agent;
    }

    /**
     * Evicts a user's conversation state and rate counter. Role agents remain cached.
     */
    @Override
    public void evict(@NonNull String userId) {
        chatMemoryCache.asMap().keySet().removeIf(key -> key.startsWith(userId + MEMORY_KEY_SEPARATOR));
        requestCountCache.invalidate(userId);
    }

    private void prebuildRoleAgents() {
        long startNanos = System.nanoTime();
        int prebuilt = 0;
        for (String role : toolRegistry.preloadableRoles()) {
            try {
                roleAgentCache.put(role, buildAgent(role));
                prebuilt++;
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to prebuild MCP role agent role={}", role, exception);
            }
        }
        LOGGER.info("Prebuilt {} MCP role agents in {} ms", prebuilt, elapsedMs(startNanos));
    }

    private @NonNull ChatMemory chatMemoryFor(@NonNull Object memoryId) {
        return chatMemoryCache.get(
                String.valueOf(memoryId), ignored -> MessageWindowChatMemory.withMaxMessages(memoryMaxMessages));
    }

    private static @NonNull String memoryKey(@NonNull String userId, @NonNull String role) {
        return userId + MEMORY_KEY_SEPARATOR + role;
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
