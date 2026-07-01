package com.positivity.mcp.internal.orchestration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.positivity.mcp.internal.classification.SimpleChatRuleCatalog;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.memory.SemanticChatMemoryStore;
import com.positivity.mcp.internal.orchestration.memory.SessionSummary;
import com.positivity.mcp.internal.orchestration.rag.ScopedContentRetrieverFactory;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import com.positivity.mcp.internal.service.PermissionCodes;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.SystemPromptDefaults;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryFactory;
import com.positivity.mcp.internal.telemetry.NltiTelemetryEmitter;
import com.positivity.mcp.service.AgentOrchestrationService;
import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.RolePromptResolver;
import com.positivity.mcp.service.RolePromptResolver.AssembledPrompt;
import com.positivity.mcp.service.SessionAgentCacheMetrics;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("alpha")
public class SessionAgentManager implements AgentOrchestrationService, SessionAgentCacheMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionAgentManager.class);
    private static final String MEMORY_KEY_SEPARATOR = "::";
    private static final String FULL_TOOL_CACHE_KEY = "full";
    private static final int TIER2_EXPANDED_QUERY_LIMIT = 3;
    private static final int TIER2_RETRIEVAL_CANDIDATES = 20;
    private static final int TIER2_FINAL_TOP_K = 5;

    private final Cache<String, PosAssistant> roleAgentCache;
    private final Cache<String, ChatMemory> chatMemoryCache;
    private final Cache<String, AtomicInteger> requestCountCache;
    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final PgVectorEmbeddingStore embeddingStore;
    private final MasterAgentRegistry toolRegistry;
    private final SharedOrchestrationSupport sharedOrchestrationSupport;
    private final ToolSelectionEngine toolSelectionEngine;
    private final ScopedContentRetrieverFactory scopedContentRetrieverFactory;

    @Nullable
    private final ToolExecutionAuditLogger toolExecutionAuditLogger;

    @Nullable
    private final SessionSummary sessionSummary;

    private final RolePromptResolver rolePromptResolver;
    private final SimpleChatClassifier simpleChatClassifier;
    private final @Nullable NltiTelemetryEmitter telemetryEmitter;
    private final @Nullable OpenApiToolProvider openApiToolProvider;
    private final @Nullable RequestScopedUserContext requestScopedUserContext;

    private final int memoryMaxMessages;
    private final int rateLimitPerSession;

    public SessionAgentManager(
            @NonNull ChatModel chatModel,
            @NonNull EmbeddingModel embeddingModel,
            @NonNull PgVectorEmbeddingStore embeddingStore,
            @NonNull MasterAgentRegistry toolRegistry,
            @NonNull SharedOrchestrationSupport sharedOrchestrationSupport,
            @NonNull ToolSelectionEngine toolSelectionEngine,
            @NonNull ScopedContentRetrieverFactory scopedContentRetrieverFactory,
            @Nullable ToolExecutionAuditLogger toolExecutionAuditLogger,
            @Nullable SessionSummary sessionSummary,
            @NonNull RolePromptResolver rolePromptResolver,
            @NonNull SimpleChatClassifier simpleChatClassifier,
            @Nullable NltiTelemetryEmitter telemetryEmitter,
            @Nullable OpenApiToolProvider openApiToolProvider,
            @Nullable RequestScopedUserContext requestScopedUserContext,
            @Value("${mcp.agent.cache-ttl-minutes:30}") int cacheTtlMinutes,
            @Value("${mcp.agent.max-cached-agents:500}") int maxCachedAgents,
            @Value("${mcp.agent.memory-max-messages:100}") int memoryMaxMessages,
            @Value("${pos.nlti.rate-limit.per-session:100}") int rateLimitPerSession) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.toolRegistry = toolRegistry;
        this.sharedOrchestrationSupport = sharedOrchestrationSupport;
        this.toolSelectionEngine = toolSelectionEngine;
        this.scopedContentRetrieverFactory = scopedContentRetrieverFactory;
        this.toolExecutionAuditLogger = toolExecutionAuditLogger;
        this.sessionSummary = sessionSummary;
        this.rolePromptResolver = rolePromptResolver;
        this.simpleChatClassifier = simpleChatClassifier;
        this.telemetryEmitter = telemetryEmitter;
        this.openApiToolProvider = openApiToolProvider;
        this.requestScopedUserContext = requestScopedUserContext;
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
        this.roleAgentCache = Caffeine.newBuilder()
                .maximumSize(Math.max(1, maxCachedAgents))
                .expireAfterWrite(Duration.ofMinutes(cacheTtlMinutes))
                .build();
        prebuildRoleAgents();
    }

    /**
     * Returns a cached role agent, creating one if absent. User-specific
     * conversation state is resolved later by the chat memory provider.
     */
    @NonNull
    PosAssistant getOrCreateAgent(@NonNull String userId, @NonNull String role) {
        return roleAgentCache.get(role + MEMORY_KEY_SEPARATOR + FULL_TOOL_CACHE_KEY, ignored -> buildAgent(role));
    }

    @Override
    public @NonNull String chat(@NonNull CurrentUserContext currentUserContext, @NonNull String message) {
        String username = currentUserContext.username();
        String role = currentUserContext.primaryRole();
        AtomicInteger requestCount = requestCountCache.get(username, key -> new AtomicInteger(0));
        if (requestCount.incrementAndGet() > rateLimitPerSession) {
            requestCount.decrementAndGet();
            LOGGER.warn("Rate limit exceeded for username={} userId={}", username, currentUserContext.userId());
            throw new RateLimitExceededException("Rate limit exceeded");
        }

        long startMs = System.currentTimeMillis();
        try {
            boolean simpleChat = simpleChatClassifier.isSimpleChat(message);
            if (LOGGER.isDebugEnabled()) {
                String messagePreview = sharedOrchestrationSupport.preview(message);
                int tokenCount = tokenCount(message);
                LOGGER.debug(
                        "MCP chat request received username={} role={} simpleChat={} chars={} tokens={} preview=\"{}\"",
                        username,
                        role,
                        simpleChat,
                        message.length(),
                        tokenCount,
                        messagePreview);
            }
            if (simpleChat) {
                String messagePreview = sharedOrchestrationSupport.preview(message);
                LOGGER.debug(
                        "MCP simple chat dispatch username={} role={} preview=\"{}\"", username, role, messagePreview);
                return simpleChat(currentUserContext, message, startMs);
            }

            ToolSelectionEngine.ToolSelectionResult selection =
                    toolSelectionEngine.selectRoleTools(role, currentUserContext.permissionCodes(), message);
            List<Object> selectedTools =
                    sharedOrchestrationSupport.mergeTools(selection.roleTools(), selection.fallbackTools());
            String cacheKey = sharedOrchestrationSupport.toolCacheKey(selectedTools);
            if (LOGGER.isDebugEnabled()) {
                String messagePreview = sharedOrchestrationSupport.preview(message);
                LOGGER.debug(
                        "MCP tool selection for username={} role={} selectedTools={} preview=\"{}\"",
                        username,
                        role,
                        selectedTools.size(),
                        messagePreview);
                LOGGER.debug(
                        "MCP agent chat dispatch username={} role={} cacheKey={} roleTools={} fallbackTools={} preview=\"{}\"",
                        username,
                        role,
                        cacheKey,
                        sharedOrchestrationSupport.toolNames(selection.roleTools()),
                        sharedOrchestrationSupport.toolNames(selection.fallbackTools()),
                        messagePreview);
            }
            PosAssistant agent = getOrCreateAgent(role, cacheKey, selection.roleTools(), selection.fallbackTools());
            // Gate 3 (G3.3): publish the caller so the dynamic OpenApiToolProvider (running inside the
            // cached agent) resolves this request's permission-eligible tools; cleared in finally.
            if (requestScopedUserContext != null) {
                requestScopedUserContext.set(currentUserContext);
            }
            long agentStartNanos = System.nanoTime();
            String response = agent.chat(memoryKey(username, role), message, formatUserContext(currentUserContext));
            int elapsedMs = (int) (System.currentTimeMillis() - startMs);
            LOGGER.info(
                    "MCP agent chat completed role={} selectedTools={} modelElapsedMs={} totalElapsedMs={}",
                    role,
                    selectedTools.size(),
                    elapsedMs(agentStartNanos),
                    elapsedMs);
            if (toolExecutionAuditLogger != null) {
                toolExecutionAuditLogger.logToolExecution(null, username, true, false, elapsedMs, null);
            }
            List<String> toolNames = sharedOrchestrationSupport.toolNames(selectedTools);
            String ragScope = toolRegistry.resolveRagScopeForTools(selectedTools);
            AssembledPrompt assembled = rolePromptResolver.assemble(role, ragScope);
            List<String> promptLayers = assembled != null ? assembled.layers() : List.of();
            emitChatTelemetry(
                    currentUserContext,
                    toolNames,
                    promptLayers,
                    false,
                    null,
                    selection.workflowState().name(),
                    elapsedMs,
                    "SUCCESS",
                    null);
            return response;
        } catch (RuntimeException exception) {
            int elapsedMs = (int) (System.currentTimeMillis() - startMs);
            if (toolExecutionAuditLogger != null) {
                toolExecutionAuditLogger.logToolExecution(
                        null,
                        username,
                        false,
                        false,
                        elapsedMs,
                        exception.getClass().getSimpleName());
            }
            emitChatTelemetry(
                    currentUserContext,
                    List.of(),
                    List.of(),
                    false,
                    null,
                    null,
                    elapsedMs,
                    "ERROR",
                    exception.getClass().getSimpleName());
            throw new IllegalStateException(
                    "MCP chat failed role=%s elapsedMs=%d errorName=%s"
                            .formatted(role, elapsedMs, exception.getClass().getSimpleName()),
                    exception);
        } finally {
            if (requestScopedUserContext != null) {
                requestScopedUserContext.clear();
            }
        }
    }

    @Override
    public long getCacheSize() {
        return roleAgentCache.estimatedSize();
    }

    private PosAssistant buildAgent(@NonNull String role) {
        return buildAgent(role, toolRegistry.resolveDomainTools(role), toolSelectionEngine.fullFallbackTools());
    }

    private PosAssistant buildAgent(
            @NonNull String role, @NonNull Collection<Object> roleTools, @NonNull Collection<Object> fallbackTools) {
        long startNanos = System.nanoTime();
        List<Object> tools = sharedOrchestrationSupport.mergeTools(roleTools, fallbackTools);
        String ragScope = toolRegistry.resolveRagScopeForTools(tools);
        String promptName = SystemPromptDefaults.promptNameForRagScope(ragScope);

        // 2. Tier 2 retrieval pipeline: semantic + expanded + hybrid + re-ranking.
        ContentRetriever semanticRetriever = scopedContentRetrieverFactory.create(ragScope, 10, 0.6);
        ContentRetriever broadSemanticRetriever =
                scopedContentRetrieverFactory.create(ragScope, TIER2_RETRIEVAL_CANDIDATES, 0.55);
        ContentRetriever expandedRetriever = new QueryExpansionContentRetriever(
                broadSemanticRetriever, TIER2_EXPANDED_QUERY_LIMIT, TIER2_RETRIEVAL_CANDIDATES);
        ContentRetriever hybridRetriever =
                new HybridContentRetriever(List.of(semanticRetriever, expandedRetriever), TIER2_RETRIEVAL_CANDIDATES);
        ContentRetriever rerankedRetriever = new RerankedContentRetriever(hybridRetriever, TIER2_FINAL_TOP_K);
        ContentRetriever resilientContentRetriever =
                new ResilientContentRetriever(rerankedRetriever, "tier2-hybrid-reranked-retriever");

        // Tier 3: Role-aware metadata filtering (deferred to dynamic context resolution
        // at runtime)
        // Note: RoleAwareMetadataFilter requires user roles from SecurityContext.
        // Currently applied at chat boundary where user context is available.

        // 3. Assemble AiServices proxy. Chat memory remains per user+role through
        // the provider, so role-level agent prebuilds do not share conversations.
        // Prompt resolution is deferred per-message via systemMessageProvider so that
        // database updates to prompts are visible immediately without agent rebuild.
        var agentBuilder = AiServices.builder(PosAssistant.class)
                .chatModel(chatModel)
                .tools(tools)
                .contentRetriever(resilientContentRetriever)
                .systemMessageProvider(
                        memoryId -> rolePromptResolver.assemble(role, ragScope).text())
                .chatMemoryProvider(this::chatMemoryFor);
        if (openApiToolProvider != null) {
            // Gate 3 (G3.3): dynamic, permission-gated OpenAPI-discovered tools resolved per request
            // from RequestScopedUserContext (set around agent.chat below). A cached agent never
            // captures a caller's permissions, so it cannot leak a prior caller's tools.
            agentBuilder.toolProvider(openApiToolProvider);
        }
        PosAssistant agent = agentBuilder.build();
        LOGGER.debug(
                "Built MCP role agent role={} promptName={} ragScope={} toolNames={}",
                role,
                promptName,
                ragScope,
                sharedOrchestrationSupport.toolNames(tools));
        LOGGER.info(
                "Built MCP role agent role={} promptName={} ragScope={} tools={} in {} ms",
                role,
                promptName,
                ragScope,
                tools.size(),
                elapsedMs(startNanos));
        return agent;
    }

    private @NonNull PosAssistant getOrCreateAgent(
            @NonNull String role,
            @NonNull String toolCacheKey,
            @NonNull List<Object> roleTools,
            @NonNull List<Object> fallbackTools) {
        return roleAgentCache.get(
                role + MEMORY_KEY_SEPARATOR + toolCacheKey, ignored -> buildAgent(role, roleTools, fallbackTools));
    }

    /**
     * Evicts a user's conversation state and rate counter. Role agents remain
     * cached.
     */
    @Override
    public void evict(@NonNull String userId) {
        chatMemoryCache.asMap().keySet().removeIf(key -> key.startsWith(userId + MEMORY_KEY_SEPARATOR));
        requestCountCache.invalidate(userId);
    }

    private void prebuildRoleAgents() {
        long startNanos = System.nanoTime();
        int prebuilt = 0;
        for (String role : toolRegistry.preloadableRoleIdentifiers()) {
            try {
                // No CurrentUserContext is available during startup warm-up, so prebuild
                // with the AUTHENTICATED-only permission set; callers whose actual
                // permissionCodes differ get a cache miss and build on demand via
                // getOrCreateAgent (its key already varies with toolCacheKey).
                ToolSelectionEngine.ToolSelectionResult selection =
                        toolSelectionEngine.selectRoleTools(role, Set.of(PermissionCodes.AUTHENTICATED), role);
                List<Object> selectedTools =
                        sharedOrchestrationSupport.mergeTools(selection.roleTools(), selection.fallbackTools());
                String warmCacheKey = sharedOrchestrationSupport.toolCacheKey(selectedTools);
                roleAgentCache.put(
                        role + MEMORY_KEY_SEPARATOR + warmCacheKey,
                        buildAgent(role, selection.roleTools(), selection.fallbackTools()));

                // Keep the legacy full key warm for direct role-level access paths.
                roleAgentCache.put(role + MEMORY_KEY_SEPARATOR + FULL_TOOL_CACHE_KEY, buildAgent(role));
                prebuilt++;
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to prebuild MCP role agent role={}", role, exception);
            }
        }
        LOGGER.info("Prebuilt {} MCP role agents in {} ms", prebuilt, elapsedMs(startNanos));
    }

    private @NonNull ChatMemory chatMemoryFor(@NonNull Object memoryId) {
        // Tier 3: Replace MessageWindowChatMemory with SemanticChatMemoryStore
        // for persistent semantic memory and session summarization
        return chatMemoryCache.get(
                String.valueOf(memoryId),
                ignored -> new SemanticChatMemoryStore(
                        memoryMaxMessages, chatModel, embeddingModel, embeddingStore, sessionSummary));
    }

    private @NonNull String simpleChat(
            @NonNull CurrentUserContext currentUserContext, @NonNull String message, long requestStartMs) {
        long simpleStartNanos = System.nanoTime();
        String prompt = rolePromptResolver.resolvePrompt(SystemPromptDefaults.MASTER_PROMPT_NAME)
                + System.lineSeparator()
                + formatUserContext(currentUserContext);
        String response = chatModel
                .chat(List.of(SystemMessage.from(prompt), UserMessage.from(message)))
                .aiMessage()
                .text();
        int elapsedMs = (int) (System.currentTimeMillis() - requestStartMs);
        LOGGER.info(
                "MCP simple chat completed role={} modelElapsedMs={} totalElapsedMs={}",
                currentUserContext.primaryRole(),
                elapsedMs(simpleStartNanos),
                elapsedMs);
        if (toolExecutionAuditLogger != null) {
            toolExecutionAuditLogger.logToolExecution(
                    null, currentUserContext.username(), true, false, elapsedMs, null);
        }
        emitChatTelemetry(currentUserContext, List.of(), List.of(), true, null, null, elapsedMs, "SUCCESS", null);
        return response;
    }

    /**
     * Emits one {@code nlti.request.telemetry} event for a completed chat request (Gate 1). Never
     * throws into the request path — a telemetry failure is logged and swallowed. No-op when no
     * emitter is configured. {@code correlationId} is taken from the request MDC when present.
     */
    private void emitChatTelemetry(
            @NonNull CurrentUserContext currentUserContext,
            @NonNull List<String> selectedToolNames,
            @NonNull List<String> promptLayers,
            boolean simpleChat,
            @Nullable String simpleChatRule,
            @Nullable String workflowState,
            long totalMs,
            @NonNull String status,
            @Nullable String errorCode) {
        if (telemetryEmitter == null) {
            return;
        }
        try {
            String correlationId = MDC.get("correlationId");
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = UUID.randomUUID().toString();
            }
            telemetryEmitter.emit(NltiRequestTelemetryFactory.forChatRequest(
                    correlationId,
                    Instant.now().toString(),
                    currentUserContext.primaryRole(),
                    currentUserContext.permissionCodes().size(),
                    selectedToolNames,
                    promptLayers,
                    simpleChat,
                    simpleChatRule,
                    workflowState,
                    totalMs,
                    status,
                    errorCode));
        } catch (RuntimeException telemetryFailure) {
            LOGGER.warn(
                    "MCP telemetry emission failed role={} status={}",
                    currentUserContext.primaryRole(),
                    status,
                    telemetryFailure);
        }
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

    private static @NonNull String memoryKey(@NonNull String userId, @NonNull String role) {
        return userId + MEMORY_KEY_SEPARATOR + role;
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
