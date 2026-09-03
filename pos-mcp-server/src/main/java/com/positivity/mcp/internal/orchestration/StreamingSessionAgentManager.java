package com.positivity.mcp.internal.orchestration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.positivity.mcp.internal.classification.SimpleChatRuleCatalog;
import com.positivity.mcp.internal.client.RoleDefaultPermissionsClient;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.config.StreamingAgentOrchestrationService;
import com.positivity.mcp.internal.config.StreamingSessionAgentCacheMetrics;
import com.positivity.mcp.internal.config.TieredChatModelResolver;
import com.positivity.mcp.internal.domain.ModelTier;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.event.AgentCacheInvalidationEvent;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.orchestration.rag.ScopedContentRetrieverFactory;
import com.positivity.mcp.internal.orchestration.retrieval.PermissionAwareMetadataFilter;
import com.positivity.mcp.internal.security.PermissionCodes;
import com.positivity.mcp.internal.service.NltiRouter;
import com.positivity.mcp.internal.service.NltiWorkflowStateService;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.RolePromptResolver;
import com.positivity.mcp.internal.service.RolePromptResolver.AssembledPrompt;
import com.positivity.mcp.internal.service.SystemPromptDefaults;
import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryFactory;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryFactory.TierRouting;
import com.positivity.mcp.internal.telemetry.NltiTelemetryEmitter;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
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
    private final SimpleChatFastPath simpleChatFastPath;

    @Nullable
    private final ToolExecutionAuditLogger toolExecutionAuditLogger;

    @Nullable
    private final NltiTelemetryEmitter telemetryEmitter;

    @Nullable
    private final OpenApiToolProvider openApiToolProvider;

    @Nullable
    private final RequestScopedUserContext requestScopedUserContext;

    /** #1655: passed to the tool-calling ChatClient so advisor observations are emitted. */
    private final @Nullable ObservationRegistry observationRegistry;

    @Nullable
    private final RoleDefaultPermissionsClient roleDefaultPermissionsClient;

    @Nullable
    private final ToolInvocationRecorder toolInvocationRecorder;

    private final NltiWorkflowStateService workflowStateService;

    private final @Nullable NltiRouter nltiRouter;
    private final @Nullable TieredChatModelResolver tieredChatModelResolver;
    private final boolean tieringEnabled;

    private final Clock clock;

    // #1194: dense-retrieval similarity floors — calibrated per embedding model (see application.yml).

    private final double ragMinScore;

    private final double ragTier2MinScore;

    private final int memoryMaxMessages;
    private final int rateLimitPerSession;

    public StreamingSessionAgentManager(
            @Qualifier("streamingChatModel") @NonNull StreamingChatModel streamingChatModel,
            @NonNull MasterAgentRegistry toolRegistry,
            @NonNull SharedOrchestrationSupport sharedOrchestrationSupport,
            @NonNull ToolSelectionEngine toolSelectionEngine,
            @NonNull ScopedContentRetrieverFactory scopedContentRetrieverFactory,
            @NonNull RolePromptResolver rolePromptResolver,
            @NonNull SimpleChatFastPath simpleChatFastPath,
            @Nullable ToolExecutionAuditLogger toolExecutionAuditLogger,
            @Nullable NltiTelemetryEmitter telemetryEmitter,
            @Nullable OpenApiToolProvider openApiToolProvider,
            @Nullable RequestScopedUserContext requestScopedUserContext,
            @Nullable ObservationRegistry observationRegistry,
            @Nullable RoleDefaultPermissionsClient roleDefaultPermissionsClient,
            @Nullable ToolInvocationRecorder toolInvocationRecorder,
            @NonNull NltiWorkflowStateService workflowStateService,
            @Nullable NltiRouter nltiRouter,
            @Nullable TieredChatModelResolver tieredChatModelResolver,
            @Value("${mcp.model.tiering-enabled:false}") boolean tieringEnabled,
            @NonNull Clock clock,
            @Value("${mcp.agent.cache-ttl-minutes:30}") int cacheTtlMinutes,
            @Value("${mcp.agent.max-cached-agents:500}") int maxCachedAgents,
            @Value("${mcp.agent.memory-max-messages:100}") int memoryMaxMessages,
            @Value("${pos.nlti.rate-limit.per-session:100}") int rateLimitPerSession,
            @Value("${mcp.rag.min-score:0.45}") double ragMinScore,
            @Value("${mcp.rag.tier2-min-score:0.40}") double ragTier2MinScore) {
        this.streamingChatModel = streamingChatModel;
        this.toolRegistry = toolRegistry;
        this.sharedOrchestrationSupport = sharedOrchestrationSupport;
        this.toolSelectionEngine = toolSelectionEngine;
        this.scopedContentRetrieverFactory = scopedContentRetrieverFactory;
        this.rolePromptResolver = rolePromptResolver;
        this.simpleChatFastPath = simpleChatFastPath;
        this.toolExecutionAuditLogger = toolExecutionAuditLogger;
        this.telemetryEmitter = telemetryEmitter;
        this.openApiToolProvider = openApiToolProvider;
        this.requestScopedUserContext = requestScopedUserContext;
        this.observationRegistry = observationRegistry;
        this.roleDefaultPermissionsClient = roleDefaultPermissionsClient;
        this.toolInvocationRecorder = toolInvocationRecorder;
        this.workflowStateService = workflowStateService;
        this.nltiRouter = nltiRouter;
        this.tieredChatModelResolver = tieredChatModelResolver;
        this.tieringEnabled = tieringEnabled;
        this.clock = clock;
        this.ragMinScore = ragMinScore;
        this.ragTier2MinScore = ragTier2MinScore;
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

        // Gate 4 / Gate 2A closure: shared T0 rule fast-path (previously blocking-only) — pure
        // social chat streams straight from the default model with no tool selection or RAG.
        if (simpleChatFastPath.isSimpleChat(message)) {
            LOGGER.debug(
                    "MCP streaming simple chat dispatch username={} role={} preview=\"{}\"",
                    username,
                    role,
                    messagePreview);
            return simpleStreamChat(currentUserContext, message, startMs);
        }

        // Gate 4 (#1192): classify with the T1 router (temperature 0) and select the executor tier.
        // Null when tiering is disabled or the router is not wired — default model (rollback path).
        NltiRouter.RoutingDecision routingDecision = routeTier(message);
        ModelTier tier = routingDecision == null ? null : routingDecision.tier();

        // #778: gate tool selection by the subject's persisted session workflow state when they have
        // one; otherwise fall back to message-heuristic derivation (session-less callers).
        Optional<WorkflowState> persistedState = workflowStateService.resolveActiveState(username);
        ToolSelectionEngine.ToolSelectionResult selection = persistedState
                .map(state ->
                        toolSelectionEngine.selectRoleTools(role, currentUserContext.permissionCodes(), message, state))
                .orElseGet(
                        () -> toolSelectionEngine.selectRoleTools(role, currentUserContext.permissionCodes(), message));
        List<Object> allTools = sharedOrchestrationSupport.mergeTools(selection.roleTools(), selection.fallbackTools());
        String cacheKey = sharedOrchestrationSupport.toolCacheKey(allTools);
        LOGGER.debug(
                "MCP streaming tool selection username={} role={} cacheKey={} tools={}",
                username,
                role,
                cacheKey,
                sharedOrchestrationSupport.toolNames(allTools));
        StreamingPosAssistant agent =
                roleAgentCache.get(agentCacheKey(role, cacheKey, tier), ignored -> buildAgent(role, allTools, tier));

        // Capture on the calling thread: the Reactor completion signals run on a scheduler thread
        // where the request MDC (correlationId) and prompt layers are no longer available.
        List<String> toolNames = sharedOrchestrationSupport.toolNames(allTools);
        String ragScope = toolRegistry.resolveRagScopeForTools(allTools);
        AssembledPrompt assembled = rolePromptResolver.assemble(role, ragScope, false);
        List<String> promptLayers = assembled.layers();
        String correlationId = resolveCorrelationId();
        String workflowState = selection.workflowState().name();
        TierRouting tierRouting = tierRoutingOf(routingDecision);
        // #1193: the write-capability signal is only known once the agent resolves this request's
        // tools inside streamTokens (on the subscribing thread); captured there, read by the
        // completion signals after the thread-local holder has been cleared.
        AtomicBoolean writeCapableToolsPresent = new AtomicBoolean(false);

        String userContext = formatUserContext(currentUserContext);
        // Capture the caller's Authorization on the request thread; the Flux is subscribed later on a
        // Reactor thread where RequestContextHolder is no longer populated.
        String authorizationHeader = currentAuthorizationHeader();
        return Flux.<String>create(emitter -> streamTokens(
                        agent,
                        memoryId,
                        message,
                        userContext,
                        currentUserContext,
                        authorizationHeader,
                        writeCapableToolsPresent,
                        emitter))
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
                    boolean writeCapable = writeCapableToolsPresent.get();
                    emitStreamTelemetry(
                            correlationId,
                            currentUserContext,
                            toolNames,
                            withWriteGateLayer(promptLayers, writeCapable),
                            workflowState,
                            elapsedMs,
                            "SUCCESS",
                            null,
                            false,
                            tierRouting,
                            writeCapable);
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
                            exception.getClass().getSimpleName(),
                            false,
                            tierRouting,
                            writeCapableToolsPresent.get());
                });
    }

    /**
     * Streams a T0 (rule fast-path) reply: master prompt + caller context, default model, no tools,
     * no RAG, no memory. Mirrors the blocking manager's {@code simpleChat} (Gate 2A closure).
     */
    private @NonNull Flux<String> simpleStreamChat(
            @NonNull CurrentUserContext currentUserContext, @NonNull String message, long startMs) {
        String username = currentUserContext.username();
        String role = currentUserContext.primaryRole();
        String correlationId = resolveCorrelationId();
        Prompt prompt = simpleChatFastPath.prompt(currentUserContext, message);
        return Flux.defer(() -> streamingChatModel.stream(prompt))
                .map(response -> {
                    if (response.getResult() == null || response.getResult().getOutput() == null) {
                        return "";
                    }
                    String text = response.getResult().getOutput().getText();
                    return text == null ? "" : text;
                })
                .filter(token -> !token.isEmpty())
                .doOnComplete(() -> {
                    int elapsedMs = (int) (System.currentTimeMillis() - startMs);
                    LOGGER.debug(
                            "MCP streaming simple chat completed username={} role={} totalElapsedMs={}",
                            username,
                            role,
                            elapsedMs);
                    if (toolExecutionAuditLogger != null) {
                        toolExecutionAuditLogger.logToolExecution(null, username, true, false, elapsedMs, null);
                    }
                    emitStreamTelemetry(
                            correlationId,
                            currentUserContext,
                            List.of(),
                            List.of(),
                            null,
                            elapsedMs,
                            "SUCCESS",
                            null,
                            true,
                            null,
                            false);
                })
                .doOnError(exception -> {
                    int elapsedMs = (int) (System.currentTimeMillis() - startMs);
                    LOGGER.warn(
                            "MCP streaming simple chat failed username={} role={} error={}",
                            username,
                            role,
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
                            null,
                            elapsedMs,
                            "ERROR",
                            exception.getClass().getSimpleName(),
                            true,
                            null,
                            false);
                });
    }

    /**
     * Classifies the request via the Gate 4 T1 router; null (default model, no tier key segment)
     * when tiering is disabled ({@code mcp.model.tiering-enabled=false}, the documented rollback) or
     * the router is not wired. Never throws — router failures safe-default inside
     * {@link NltiRouter#classify}.
     */
    private NltiRouter.@Nullable RoutingDecision routeTier(@NonNull String message) {
        if (!tieringEnabled || nltiRouter == null) {
            return null;
        }
        return nltiRouter.classify(message);
    }

    private @Nullable TierRouting tierRoutingOf(NltiRouter.@Nullable RoutingDecision decision) {
        if (decision == null) {
            return null;
        }
        String tierModel =
                tieredChatModelResolver == null ? null : tieredChatModelResolver.modelNameFor(decision.tier());
        String routerModel =
                tieredChatModelResolver == null ? null : tieredChatModelResolver.modelNameFor(ModelTier.T1_ROUTER);
        return new TierRouting(
                decision.classification().intentType().name(),
                decision.classification().riskLevel().name(),
                decision.classification().domain(),
                decision.classification().complexity().name(),
                NltiRequestTelemetry.Tier.valueOf(decision.tier().name()),
                tierModel,
                routerModel);
    }

    /** Appends the WRITE_GATE layer to the captured baseline layers when the request assembled it. */
    private static @NonNull List<String> withWriteGateLayer(@NonNull List<String> layers, boolean writeCapable) {
        if (!writeCapable || layers.contains("WRITE_GATE")) {
            return layers;
        }
        List<String> withWriteGate = new ArrayList<>(layers);
        withWriteGate.add("WRITE_GATE");
        return List.copyOf(withWriteGate);
    }

    @Override
    public void evict(@NonNull String userId) {
        chatMemoryCache.asMap().keySet().removeIf(key -> key.startsWith(userId + MEMORY_KEY_SEPARATOR));
        requestCountCache.invalidate(userId);
    }

    /**
     * Drops every cached streaming role agent when runtime agent configuration changes (a
     * system-prompt write or an {@code mcp_tool_permission} grant/revoke), so the next request
     * rebuilds against the committed configuration instead of waiting out the TTL (#639). Runs
     * after the publishing transaction commits; {@code fallbackExecution} covers non-transactional
     * publishers.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAgentConfigurationChanged(@NonNull AgentCacheInvalidationEvent event) {
        long cachedAgents = roleAgentCache.estimatedSize();
        roleAgentCache.invalidateAll();
        LOGGER.info(
                "Invalidated MCP streaming role-agent cache cachedAgents={} source={} detail={}",
                cachedAgents,
                event.source(),
                event.detail());
    }

    @Override
    public long getCacheSize() {
        return roleAgentCache.estimatedSize();
    }

    private @NonNull StreamingPosAssistant buildAgent(@NonNull String role) {
        List<Object> tools = sharedOrchestrationSupport.mergeTools(
                toolRegistry.resolveDomainTools(role), toolSelectionEngine.fullFallbackTools());
        return buildAgent(role, tools, null);
    }

    private @NonNull StreamingPosAssistant buildAgent(
            @NonNull String role, @NonNull List<Object> tools, @Nullable ModelTier tier) {
        long startNanos = System.nanoTime();
        String ragScope = toolRegistry.resolveRagScopeForTools(tools);
        String promptName = SystemPromptDefaults.promptNameForRagScope(ragScope);
        QueryDocumentRetriever semanticRetriever = scopedContentRetrieverFactory.create(ragScope, 10, ragMinScore);
        QueryDocumentRetriever broadSemanticRetriever =
                scopedContentRetrieverFactory.create(ragScope, TIER2_RETRIEVAL_CANDIDATES, ragTier2MinScore);
        QueryDocumentRetriever expandedRetriever = new QueryExpansionContentRetriever(
                broadSemanticRetriever, TIER2_EXPANDED_QUERY_LIMIT, TIER2_RETRIEVAL_CANDIDATES);
        // #784: dense + query-expansion, plus the lexical (FTS) source when enabled. RRF fusion when
        // lexical is present; otherwise the original insertion-order merge, so the dense-only path is
        // byte-for-byte unchanged when the feature flag is off.
        List<QueryDocumentRetriever> hybridSources = new ArrayList<>(List.of(semanticRetriever, expandedRetriever));
        Optional<QueryDocumentRetriever> lexicalRetriever = scopedContentRetrieverFactory.createLexical(ragScope);
        lexicalRetriever.ifPresent(hybridSources::add);
        QueryDocumentRetriever hybridRetriever = lexicalRetriever.isPresent()
                ? HybridContentRetriever.reciprocalRankFusion(
                        hybridSources, TIER2_RETRIEVAL_CANDIDATES, scopedContentRetrieverFactory.rrfK())
                : new HybridContentRetriever(hybridSources, TIER2_RETRIEVAL_CANDIDATES);
        // #1124 item 4: permission-gate candidates BEFORE the top-K re-rank (see SessionAgentManager),
        // so the top-K is chosen from caller-visible docs and the broadened master scope cannot leak
        // gated docs. Codes are read per request from the thread-local caller context.
        QueryDocumentRetriever permissionFilteredRetriever = permissionFiltered(hybridRetriever);
        QueryDocumentRetriever rerankedRetriever =
                new RerankedContentRetriever(permissionFilteredRetriever, TIER2_FINAL_TOP_K);
        QueryDocumentRetriever resilientContentRetriever =
                new ResilientContentRetriever(rerankedRetriever, "tier2-hybrid-reranked-retriever");

        // #1193 cache safety: the WRITE-GATE layer is applied per request (the supplier reads the
        // request-scoped write-capability signal, resolved the same way tools are), so a cached
        // agent can never bake a WRITE_GATE prompt into requests without write-capable tools.
        StreamingPosAssistant agent = new SpringAiStreamingPosAssistant(
                executorStreamingChatModel(tier),
                () -> rolePromptResolver
                        .assemble(role, ragScope, currentWriteCapableToolsPresent())
                        .text(),
                tools,
                resilientContentRetriever,
                this::chatMemoryFor,
                openApiToolProvider,
                toolInvocationRecorder,
                requestScopedUserContext,
                observationRegistry);
        LOGGER.debug(
                "Built MCP streaming role agent role={} promptName={} ragScope={} tier={} toolNames={}",
                role,
                promptName,
                ragScope,
                tier,
                sharedOrchestrationSupport.toolNames(tools));
        LOGGER.info(
                "Built MCP streaming role agent role={} promptName={} ragScope={} tier={} tools={} in {} ms",
                role,
                promptName,
                ragScope,
                tier,
                tools.size(),
                elapsedMs(startNanos));
        return agent;
    }

    /** The streaming model serving {@code tier}; the default model when untiered or no resolver wired. */
    private @NonNull StreamingChatModel executorStreamingChatModel(@Nullable ModelTier tier) {
        if (tier == null || tieredChatModelResolver == null) {
            return streamingChatModel;
        }
        return tieredChatModelResolver.streamingChatModelFor(tier);
    }

    private boolean currentWriteCapableToolsPresent() {
        return requestScopedUserContext != null && requestScopedUserContext.currentWriteCapableToolsPresent();
    }

    /**
     * Cache key for a streaming role agent: {@code role::toolCacheKey[::tier]}. Gate 4 cache safety:
     * the tier is part of the key, so a T2-simple agent (small model) is never reused for a
     * T2-complex request or vice versa. Untiered agents keep the historical shape.
     */
    private static @NonNull String agentCacheKey(
            @NonNull String role, @NonNull String toolCacheKey, @Nullable ModelTier tier) {
        String base = role + MEMORY_KEY_SEPARATOR + toolCacheKey;
        return tier == null ? base : base + MEMORY_KEY_SEPARATOR + tier.name();
    }

    /**
     * Wraps a retriever so each retrieval is permission-gated with the current request's caller codes
     * (read from the thread-local {@link RequestScopedUserContext}); fail-closed to public-only docs
     * when no caller is published. Mirrors {@code SessionAgentManager#permissionFiltered}.
     */
    private @NonNull QueryDocumentRetriever permissionFiltered(@NonNull QueryDocumentRetriever delegate) {
        return queryText -> {
            Set<String> callerCodes = requestScopedUserContext == null
                    ? Set.of()
                    : requestScopedUserContext
                            .current()
                            .map(CurrentUserContext::permissionCodes)
                            .map(Set::copyOf)
                            .orElseGet(Set::of);
            return new PermissionAwareMetadataFilter(delegate, callerCodes).retrieve(queryText);
        };
    }

    private void streamTokens(
            @NonNull StreamingPosAssistant agent,
            @NonNull String memoryId,
            @NonNull String message,
            @NonNull String userContext,
            @NonNull CurrentUserContext currentUserContext,
            @Nullable String authorizationHeader,
            @NonNull AtomicBoolean writeCapableToolsPresent,
            @NonNull FluxSink<String> emitter) {
        // Publish the caller for OpenApiToolProvider.provideTools, which the tool callback resolver invokes
        // synchronously while building the request context inside start(). Cleared in finally on this
        // same thread — before any async token callback — so a pooled Reactor thread cannot leak the
        // caller to a later request; if provideTools ran after the clear it would just fail-closed.
        if (requestScopedUserContext != null) {
            requestScopedUserContext.set(currentUserContext, authorizationHeader);
        }
        try {
            // agent.chat resolves this request's tools synchronously at Flux-assembly time, so the
            // write-capability signal (#1193) is recorded in the holder by the time it returns —
            // capture it for the completion-signal telemetry before the finally-clear below.
            Flux<String> tokens = agent.chat(memoryId, message, userContext);
            writeCapableToolsPresent.set(currentWriteCapableToolsPresent());
            tokens.doOnNext(token -> {
                        if (!emitter.isCancelled()) {
                            emitter.next(token);
                        }
                    })
                    .doOnComplete(emitter::complete)
                    .doOnError(emitter::error)
                    .subscribe();
        } finally {
            if (requestScopedUserContext != null) {
                requestScopedUserContext.clear();
            }
        }
    }

    private void prebuildRoleAgents() {
        long startNanos = System.nanoTime();
        int prebuilt = 0;
        // Gate 4: warm the T2-complex agent (the safe-default routing target) when tiering is
        // active; simple-tier agents build on first demand. Untiered warm-up otherwise.
        ModelTier warmTier =
                (tieringEnabled && nltiRouter != null && tieredChatModelResolver != null) ? ModelTier.T2_COMPLEX : null;
        for (String role : toolRegistry.preloadableRoleIdentifiers()) {
            try {
                // No CurrentUserContext is available during startup warm-up. #782: seed the role's
                // real default permissions from pos-security-service (fail-soft — empty on error) so
                // the warm cache matches the role's actual gated tool set; always include AUTHENTICATED.
                // Callers whose actual permissionCodes still differ get a cache miss and build on
                // demand (its key already varies with toolCacheKey).
                ToolSelectionEngine.ToolSelectionResult selection =
                        toolSelectionEngine.selectRoleTools(role, prebuildPermissionCodes(role), role);
                List<Object> selectedTools =
                        sharedOrchestrationSupport.mergeTools(selection.roleTools(), selection.fallbackTools());
                String warmCacheKey = sharedOrchestrationSupport.toolCacheKey(selectedTools);
                roleAgentCache.put(
                        agentCacheKey(role, warmCacheKey, warmTier), buildAgent(role, selectedTools, warmTier));

                // Keep the legacy full key warm for direct role-level access paths.
                roleAgentCache.put(role + MEMORY_KEY_SEPARATOR + FULL_TOOL_CACHE_KEY, buildAgent(role));
                prebuilt++;
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to prebuild MCP streaming role agent role={}", role, exception);
            }
        }
        LOGGER.info("Prebuilt {} MCP streaming role agents in {} ms", prebuilt, elapsedMs(startNanos));
    }

    private @NonNull Set<String> prebuildPermissionCodes(@NonNull String role) {
        Set<String> permissionCodes = new HashSet<>();
        permissionCodes.add(PermissionCodes.AUTHENTICATED);
        if (roleDefaultPermissionsClient != null) {
            permissionCodes.addAll(roleDefaultPermissionsClient.defaultPermissions(role));
        }
        return permissionCodes;
    }

    private @NonNull ChatMemory chatMemoryFor(@NonNull Object memoryId) {
        return chatMemoryCache.get(
                String.valueOf(memoryId),
                ignored -> MessageWindowChatMemory.builder()
                        .maxMessages(memoryMaxMessages)
                        .build());
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

    private @NonNull String formatUserContext(@NonNull CurrentUserContext currentUserContext) {
        return sharedOrchestrationSupport.formatUserContext(currentUserContext);
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
            @Nullable String errorCode,
            boolean simpleChat,
            @Nullable TierRouting tierRouting,
            boolean writeCapableToolsPresent) {
        if (telemetryEmitter == null) {
            return;
        }
        try {
            telemetryEmitter.emit(NltiRequestTelemetryFactory.forChatRequest(
                    correlationId,
                    Instant.now(clock).toString(),
                    currentUserContext.primaryRole(),
                    currentUserContext.permissionCodes().size(),
                    selectedToolNames,
                    // Streaming is fail-closed for openapi tools (Reactor-context propagation deferred).
                    List.of(),
                    promptLayers,
                    simpleChat,
                    null,
                    workflowState,
                    totalMs,
                    status,
                    errorCode,
                    tierRouting,
                    writeCapableToolsPresent));
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
