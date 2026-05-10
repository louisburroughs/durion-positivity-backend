package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.positivity.mcp.internal.classification.SimpleChatRuleCatalog;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.orchestration.rag.ScopedContentRetrieverFactory;
import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.RolePromptResolver;
import com.positivity.mcp.service.StreamingAgentOrchestrationService;
import com.positivity.mcp.service.StreamingSessionAgentCacheMetrics;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
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
    private static final String FULL_TOOL_CACHE_KEY = "full";
    private static final int TIER2_EXPANDED_QUERY_LIMIT = 3;
    private static final int TIER2_RETRIEVAL_CANDIDATES = 20;
    private static final int TIER2_FINAL_TOP_K = 5;

    private final Cache<String, StreamingPosAssistant> roleAgentCache;
    private final Cache<String, ChatMemory> chatMemoryCache;
    private final Cache<String, AtomicInteger> requestCountCache;
    private final StreamingChatModel streamingChatModel;
    private final MasterAgentRegistry toolRegistry;
    private final SharedOrchestrationSupport sharedOrchestrationSupport;
    private final ToolSelectionEngine toolSelectionEngine;
    private final ScopedContentRetrieverFactory scopedContentRetrieverFactory;
    private final RolePromptResolver rolePromptResolver;

    @Nullable
    private final ToolExecutionAuditLogger toolExecutionAuditLogger;

    private final int memoryMaxMessages;
    private final int rateLimitPerSession;

    public StreamingSessionAgentManager(
            @NonNull StreamingChatModel streamingChatModel,
            @NonNull MasterAgentRegistry toolRegistry,
            @NonNull SharedOrchestrationSupport sharedOrchestrationSupport,
            @NonNull ToolSelectionEngine toolSelectionEngine,
            @NonNull ScopedContentRetrieverFactory scopedContentRetrieverFactory,
            @NonNull RolePromptResolver rolePromptResolver,
            @Nullable ToolExecutionAuditLogger toolExecutionAuditLogger,
            @Value("${mcp.agent.cache-ttl-minutes:30}") int cacheTtlMinutes,
            @Value("${mcp.agent.max-cached-agents:500}") int maxCachedAgents,
            @Value("${mcp.agent.memory-max-messages:100}") int memoryMaxMessages,
            @Value("${pos.nlti.rate-limit.per-session:100}") int rateLimitPerSession) {
        this.streamingChatModel = streamingChatModel;
        this.toolRegistry = toolRegistry;
        this.sharedOrchestrationSupport = sharedOrchestrationSupport;
        this.toolSelectionEngine = toolSelectionEngine;
        this.scopedContentRetrieverFactory = scopedContentRetrieverFactory;
        this.rolePromptResolver = rolePromptResolver;
        this.toolExecutionAuditLogger = toolExecutionAuditLogger;
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
        this.roleAgentCache = Caffeine.newBuilder()
                .maximumSize(sanitizedMaxCachedAgents)
                .expireAfterWrite(Duration.ofMinutes(cacheTtlMinutes))
                .build();
        prebuildRoleAgents();
    }

    @Override
    public @NonNull Flux<String> streamChat(@NonNull CurrentUserContext currentUserContext, @NonNull String message) {
        String username = currentUserContext.username();
        String role = currentUserContext.primaryRole();
        AtomicInteger requestCount = requestCountCache.get(username, key -> new AtomicInteger(0));
        if (requestCount.incrementAndGet() > rateLimitPerSession) {
            requestCount.decrementAndGet();
            LOGGER.warn("Rate limit exceeded for username={} userId={}", username, currentUserContext.userId());
            throw new RateLimitExceededException("Rate limit exceeded");
        }

        long startMs = System.currentTimeMillis();
        String memoryId = memoryKey(username, role);
        String messagePreview = sharedOrchestrationSupport.preview(message);
        LOGGER.debug(
                "MCP streaming chat dispatch username={} role={} chars={} tokens={} preview=\"{}\"",
                username,
                role,
                message.length(),
                tokenCount(message),
                messagePreview);

        ToolSelectionEngine.ToolSelectionResult selection = toolSelectionEngine.selectRoleTools(role, message);
        List<Object> allTools = sharedOrchestrationSupport.mergeTools(selection.roleTools(), selection.fallbackTools());
        String cacheKey = sharedOrchestrationSupport.toolCacheKey(allTools);
        LOGGER.debug(
                "MCP streaming tool selection username={} role={} cacheKey={} tools={}",
                username,
                role,
                cacheKey,
                sharedOrchestrationSupport.toolNames(allTools));
        StreamingPosAssistant agent = roleAgentCache.get(role + MEMORY_KEY_SEPARATOR + cacheKey,
                ignored -> buildAgent(role, allTools));

        String userContext = formatUserContext(currentUserContext);
        return Flux.<String>create(emitter -> streamTokens(agent, memoryId, message, userContext, emitter))
                .doOnComplete(() -> {
                    int elapsedMs = (int) (System.currentTimeMillis() - startMs);
                    LOGGER.debug(
                            "MCP streaming chat completed username={} role={} totalElapsedMs={} preview=\"{}\"",
                            username,
                            role,
                            elapsedMs,
                            messagePreview);
                    if (toolExecutionAuditLogger != null) {
                        toolExecutionAuditLogger.logToolExecution(null, username, true, false, elapsedMs, null);
                    }
                })
                .doOnError(exception -> {
                    LOGGER.warn(
                            "MCP streaming chat failed username={} role={} preview=\"{}\" error={}",
                            username,
                            role,
                            messagePreview,
                            exception.getClass().getSimpleName());
                    if (toolExecutionAuditLogger != null) {
                        toolExecutionAuditLogger.logToolExecution(
                                null,
                                username,
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

    private @NonNull StreamingPosAssistant buildAgent(@NonNull String role) {
        List<Object> tools = sharedOrchestrationSupport.mergeTools(
                toolRegistry.resolveDomainTools(role), toolSelectionEngine.fullFallbackTools());
        return buildAgent(role, tools);
    }

    private @NonNull StreamingPosAssistant buildAgent(@NonNull String role, @NonNull List<Object> tools) {
        long startNanos = System.nanoTime();
        String ragScope = toolRegistry.resolveRagScopeForTools(tools);
        ContentRetriever semanticRetriever = scopedContentRetrieverFactory.create(ragScope, 10, 0.6);
        ContentRetriever broadSemanticRetriever = scopedContentRetrieverFactory.create(ragScope,
                TIER2_RETRIEVAL_CANDIDATES, 0.55);
        ContentRetriever expandedRetriever = new QueryExpansionContentRetriever(
                broadSemanticRetriever, TIER2_EXPANDED_QUERY_LIMIT, TIER2_RETRIEVAL_CANDIDATES);
        ContentRetriever hybridRetriever = new HybridContentRetriever(List.of(semanticRetriever, expandedRetriever),
                TIER2_RETRIEVAL_CANDIDATES);
        ContentRetriever rerankedRetriever = new RerankedContentRetriever(hybridRetriever, TIER2_FINAL_TOP_K);
        ContentRetriever resilientContentRetriever = new ResilientContentRetriever(rerankedRetriever,
                "tier2-hybrid-reranked-retriever");

        StreamingPosAssistant agent = AiServices.builder(StreamingPosAssistant.class)
                .streamingChatModel(streamingChatModel)
                .tools(tools)
                .contentRetriever(resilientContentRetriever)
                .systemMessageProvider(memoryId -> rolePromptResolver.resolvePrompt(role))
                .chatMemoryProvider(this::chatMemoryFor)
                .build();
        LOGGER.debug(
                "Built MCP streaming role agent role={} ragScope={} toolNames={}",
                role,
                ragScope,
                sharedOrchestrationSupport.toolNames(tools));
        LOGGER.info(
                "Built MCP streaming role agent role={} ragScope={} tools={} in {} ms",
                role,
                ragScope,
                tools.size(),
                elapsedMs(startNanos));
        return agent;
    }

    private void streamTokens(
            @NonNull StreamingPosAssistant agent,
            @NonNull String memoryId,
            @NonNull String message,
            @NonNull String userContext,
            @NonNull FluxSink<String> emitter) {
        agent.chat(memoryId, message, userContext)
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
        for (String role : toolRegistry.preloadableRoleIdentifiers()) {
            try {
                ToolSelectionEngine.ToolSelectionResult selection = toolSelectionEngine.selectRoleTools(role, role);
                List<Object> selectedTools = sharedOrchestrationSupport.mergeTools(selection.roleTools(),
                        selection.fallbackTools());
                String warmCacheKey = sharedOrchestrationSupport.toolCacheKey(selectedTools);
                roleAgentCache.put(role + MEMORY_KEY_SEPARATOR + warmCacheKey, buildAgent(role, selectedTools));

                // Keep the legacy full key warm for direct role-level access paths.
                roleAgentCache.put(role + MEMORY_KEY_SEPARATOR + FULL_TOOL_CACHE_KEY, buildAgent(role));
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

    private static @NonNull String formatUserContext(@NonNull CurrentUserContext currentUserContext) {
        return "Authenticated user context: username=" + currentUserContext.username()
                + ", userId=" + currentUserContext.userId()
                + ", primaryRole=" + currentUserContext.primaryRole()
                + ", roles=" + currentUserContext.roles()
                + ", authorityCount=" + currentUserContext.authorities().size()
                + ". Interpret references to 'me', 'my', or 'current user' as this authenticated user."
                + " If a question depends on the user's exact permissions, prefer a self-service permissions tool before asking for identifiers.";
    }

    private static int tokenCount(@NonNull String text) {
        return SimpleChatRuleCatalog.tokenize(SimpleChatRuleCatalog.normalize(text))
                .size();
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
