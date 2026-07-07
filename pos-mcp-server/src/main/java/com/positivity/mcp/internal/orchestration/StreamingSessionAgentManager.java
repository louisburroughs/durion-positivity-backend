package com.positivity.mcp.internal.orchestration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.positivity.mcp.internal.classification.SimpleChatRuleCatalog;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.rag.ScopedContentRetrieverFactory;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import com.positivity.mcp.internal.service.PermissionCodes;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.SystemPromptDefaults;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryFactory;
import com.positivity.mcp.internal.telemetry.NltiTelemetryEmitter;
import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.RolePromptResolver;
import com.positivity.mcp.service.RolePromptResolver.AssembledPrompt;
import com.positivity.mcp.service.StreamingAgentOrchestrationService;
import com.positivity.mcp.service.StreamingSessionAgentCacheMetrics;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
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

    @Nullable
    private final NltiTelemetryEmitter telemetryEmitter;

    @Nullable
    private final OpenApiToolProvider openApiToolProvider;

    @Nullable
    private final RequestScopedUserContext requestScopedUserContext;

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
            @Nullable NltiTelemetryEmitter telemetryEmitter,
            @Nullable OpenApiToolProvider openApiToolProvider,
            @Nullable RequestScopedUserContext requestScopedUserContext,
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
        this.telemetryEmitter = telemetryEmitter;
        this.openApiToolProvider = openApiToolProvider;
        this.requestScopedUserContext = requestScopedUserContext;
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

        ToolSelectionEngine.ToolSelectionResult selection =
                toolSelectionEngine.selectRoleTools(role, currentUserContext.permissionCodes(), message);
        List<Object> allTools = sharedOrchestrationSupport.mergeTools(selection.roleTools(), selection.fallbackTools());
        String cacheKey = sharedOrchestrationSupport.toolCacheKey(allTools);
        LOGGER.debug(
                "MCP streaming tool selection username={} role={} cacheKey={} tools={}",
                username,
                role,
                cacheKey,
                sharedOrchestrationSupport.toolNames(allTools));
        StreamingPosAssistant agent =
                roleAgentCache.get(role + MEMORY_KEY_SEPARATOR + cacheKey, ignored -> buildAgent(role, allTools));

        // Capture on the calling thread: the Reactor completion signals run on a scheduler thread
        // where the request MDC (correlationId) and prompt layers are no longer available.
        List<String> toolNames = sharedOrchestrationSupport.toolNames(allTools);
        String ragScope = toolRegistry.resolveRagScopeForTools(allTools);
        AssembledPrompt assembled = rolePromptResolver.assemble(role, ragScope);
        List<String> promptLayers = assembled.layers();
        String correlationId = resolveCorrelationId();
        String workflowState = selection.workflowState().name();

        String userContext = formatUserContext(currentUserContext);
        // Capture the caller's Authorization on the request thread; the Flux is subscribed later on a
        // Reactor thread where RequestContextHolder is no longer populated.
        String authorizationHeader = currentAuthorizationHeader();
        return Flux.<String>create(emitter -> streamTokens(
                        agent, memoryId, message, userContext, currentUserContext, authorizationHeader, emitter))
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
                    emitStreamTelemetry(
                            correlationId,
                            currentUserContext,
                            toolNames,
                            promptLayers,
                            workflowState,
                            elapsedMs,
                            "SUCCESS",
                            null);
                })
                .doOnError(exception -> {
                    int elapsedMs = (int) (System.currentTimeMillis() - startMs);
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
                                elapsedMs,
                                exception.getClass().getSimpleName());
                    }
                    emitStreamTelemetry(
                            correlationId,
                            currentUserContext,
                            List.of(),
                            List.of(),
                            workflowState,
                            elapsedMs,
                            "ERROR",
                            exception.getClass().getSimpleName());
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
        String promptName = SystemPromptDefaults.promptNameForRagScope(ragScope);
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

        // Prompt resolution is deferred per-message via systemMessageProvider so that
        // database updates to prompts are visible immediately without agent rebuild.
        var agentBuilder = AiServices.builder(StreamingPosAssistant.class)
                .streamingChatModel(streamingChatModel)
                .tools(tools)
                .contentRetriever(resilientContentRetriever)
                .systemMessageProvider(
                        memoryId -> rolePromptResolver.assemble(role, ragScope).text())
                .chatMemoryProvider(this::chatMemoryFor);
        if (openApiToolProvider != null) {
            // Discovered OpenAPI tools are surfaced per request; the caller context is published on
            // the streaming thread in streamTokens (fail-closed when absent).
            agentBuilder.toolProvider(openApiToolProvider);
        }
        StreamingPosAssistant agent = agentBuilder.build();
        LOGGER.debug(
                "Built MCP streaming role agent role={} promptName={} ragScope={} toolNames={}",
                role,
                promptName,
                ragScope,
                sharedOrchestrationSupport.toolNames(tools));
        LOGGER.info(
                "Built MCP streaming role agent role={} promptName={} ragScope={} tools={} in {} ms",
                role,
                promptName,
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
            @NonNull CurrentUserContext currentUserContext,
            @Nullable String authorizationHeader,
            @NonNull FluxSink<String> emitter) {
        // Publish the caller for OpenApiToolProvider.provideTools, which langchain4j invokes
        // synchronously while building the request context inside start(). Cleared in finally on this
        // same thread — before any async token callback — so a pooled Reactor thread cannot leak the
        // caller to a later request; if provideTools ran after the clear it would just fail-closed.
        if (requestScopedUserContext != null) {
            requestScopedUserContext.set(currentUserContext, authorizationHeader);
        }
        try {
            agent.chat(memoryId, message, userContext)
                    .onPartialResponse(token -> {
                        if (!emitter.isCancelled()) {
                            emitter.next(token);
                        }
                    })
                    .onCompleteResponse(response -> emitter.complete())
                    .onError(emitter::error)
                    .start();
        } finally {
            if (requestScopedUserContext != null) {
                requestScopedUserContext.clear();
            }
        }
    }

    private void prebuildRoleAgents() {
        long startNanos = System.nanoTime();
        int prebuilt = 0;
        for (String role : toolRegistry.preloadableRoleIdentifiers()) {
            try {
                // No CurrentUserContext is available during startup warm-up, so prebuild
                // with the AUTHENTICATED-only permission set; callers whose actual
                // permissionCodes differ get a cache miss and build on demand (its key
                // already varies with toolCacheKey).
                ToolSelectionEngine.ToolSelectionResult selection =
                        toolSelectionEngine.selectRoleTools(role, Set.of(PermissionCodes.AUTHENTICATED), role);
                List<Object> selectedTools =
                        sharedOrchestrationSupport.mergeTools(selection.roleTools(), selection.fallbackTools());
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

    private @Nullable String currentAuthorizationHeader() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getHeader("Authorization");
        }
        return null;
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

    /**
     * Emits one {@code nlti.request.telemetry} event for a completed streaming request (Gate 1).
     * Called from Reactor completion signals; {@code correlationId} is captured on the calling
     * thread and passed in. Never throws into the stream; no-op when no emitter is configured.
     */
    private void emitStreamTelemetry(
            @NonNull String correlationId,
            @NonNull CurrentUserContext currentUserContext,
            @NonNull List<String> selectedToolNames,
            @NonNull List<String> promptLayers,
            @Nullable String workflowState,
            long totalMs,
            @NonNull String status,
            @Nullable String errorCode) {
        if (telemetryEmitter == null) {
            return;
        }
        try {
            telemetryEmitter.emit(NltiRequestTelemetryFactory.forChatRequest(
                    correlationId,
                    Instant.now().toString(),
                    currentUserContext.primaryRole(),
                    currentUserContext.permissionCodes().size(),
                    selectedToolNames,
                    // Streaming is fail-closed for openapi tools (Reactor-context propagation deferred).
                    List.of(),
                    promptLayers,
                    false,
                    null,
                    workflowState,
                    totalMs,
                    status,
                    errorCode));
        } catch (RuntimeException telemetryFailure) {
            LOGGER.warn(
                    "MCP streaming telemetry emission failed role={} status={}",
                    currentUserContext.primaryRole(),
                    status,
                    telemetryFailure);
        }
    }

    private static @NonNull String resolveCorrelationId() {
        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return correlationId;
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
