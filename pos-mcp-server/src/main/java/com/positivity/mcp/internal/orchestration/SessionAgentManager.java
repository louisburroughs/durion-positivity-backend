package com.positivity.mcp.internal.orchestration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.positivity.mcp.internal.classification.SimpleChatRuleCatalog;
import com.positivity.mcp.internal.client.RoleDefaultPermissionsClient;
import com.positivity.mcp.internal.config.TieredChatModelResolver;
import com.positivity.mcp.internal.domain.ModelTier;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.event.AgentCacheInvalidationEvent;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.memory.SemanticChatMemoryStore;
import com.positivity.mcp.internal.orchestration.memory.SessionSummary;
import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.orchestration.rag.ScopedContentRetrieverFactory;
import com.positivity.mcp.internal.orchestration.retrieval.PermissionAwareMetadataFilter;
import com.positivity.mcp.internal.service.AnswerResolutionLadder;
import com.positivity.mcp.internal.service.NltiRouter;
import com.positivity.mcp.internal.service.NltiWorkflowStateService;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import com.positivity.mcp.internal.service.PermissionCodes;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.SystemPromptDefaults;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryFactory;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryFactory.TierRouting;
import com.positivity.mcp.internal.telemetry.NltiTelemetryEmitter;
import com.positivity.mcp.service.AgentOrchestrationService;
import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.RolePromptResolver;
import com.positivity.mcp.service.RolePromptResolver.AssembledPrompt;
import com.positivity.mcp.service.SessionAgentCacheMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
    private final PgVectorStore embeddingStore;
    private final MasterAgentRegistry toolRegistry;
    private final SharedOrchestrationSupport sharedOrchestrationSupport;
    private final ToolSelectionEngine toolSelectionEngine;
    private final ScopedContentRetrieverFactory scopedContentRetrieverFactory;

    @Nullable
    private final ToolExecutionAuditLogger toolExecutionAuditLogger;

    @Nullable
    private final SessionSummary sessionSummary;

    private final RolePromptResolver rolePromptResolver;
    private final SimpleChatFastPath simpleChatFastPath;
    private final @Nullable NltiTelemetryEmitter telemetryEmitter;
    private final @Nullable OpenApiToolProvider openApiToolProvider;
    private final @Nullable AnswerResolutionLadder answerResolutionLadder;
    private final @Nullable RequestScopedUserContext requestScopedUserContext;
    private final @Nullable RoleDefaultPermissionsClient roleDefaultPermissionsClient;
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

    public SessionAgentManager(
            @Qualifier("chatModel") @NonNull ChatModel chatModel,
            @NonNull EmbeddingModel embeddingModel,
            @NonNull PgVectorStore embeddingStore,
            @NonNull MasterAgentRegistry toolRegistry,
            @NonNull SharedOrchestrationSupport sharedOrchestrationSupport,
            @NonNull ToolSelectionEngine toolSelectionEngine,
            @NonNull ScopedContentRetrieverFactory scopedContentRetrieverFactory,
            @Nullable ToolExecutionAuditLogger toolExecutionAuditLogger,
            @Nullable SessionSummary sessionSummary,
            @NonNull RolePromptResolver rolePromptResolver,
            @NonNull SimpleChatFastPath simpleChatFastPath,
            @Nullable NltiTelemetryEmitter telemetryEmitter,
            @Nullable OpenApiToolProvider openApiToolProvider,
            @Nullable AnswerResolutionLadder answerResolutionLadder,
            @Nullable RequestScopedUserContext requestScopedUserContext,
            @Nullable RoleDefaultPermissionsClient roleDefaultPermissionsClient,
            @NonNull NltiWorkflowStateService workflowStateService,
            @Nullable NltiRouter nltiRouter,
            @Nullable TieredChatModelResolver tieredChatModelResolver,
            @Value("${mcp.model.tiering-enabled:true}") boolean tieringEnabled,
            @NonNull Clock clock,
            @Value("${mcp.agent.cache-ttl-minutes:30}") int cacheTtlMinutes,
            @Value("${mcp.agent.max-cached-agents:500}") int maxCachedAgents,
            @Value("${mcp.agent.memory-max-messages:100}") int memoryMaxMessages,
            @Value("${pos.nlti.rate-limit.per-session:100}") int rateLimitPerSession,
            @Value("${mcp.rag.min-score:0.45}") double ragMinScore,
            @Value("${mcp.rag.tier2-min-score:0.40}") double ragTier2MinScore) {
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
        this.simpleChatFastPath = simpleChatFastPath;
        this.telemetryEmitter = telemetryEmitter;
        this.openApiToolProvider = openApiToolProvider;
        this.answerResolutionLadder = answerResolutionLadder;
        this.requestScopedUserContext = requestScopedUserContext;
        this.roleDefaultPermissionsClient = roleDefaultPermissionsClient;
        this.workflowStateService = workflowStateService;
        this.nltiRouter = nltiRouter;
        this.tieredChatModelResolver = tieredChatModelResolver;
        this.tieringEnabled = tieringEnabled;
        this.clock = clock;
        this.ragMinScore = ragMinScore;
        this.ragTier2MinScore = ragTier2MinScore;
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
     * Returns a cached role agent, creating one if absent. User-specific conversation state is
     * resolved later by the chat memory provider.
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
        NltiRouter.RoutingDecision routingDecision = null;
        try {
            boolean simpleChat = simpleChatFastPath.isSimpleChat(message);
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

            // Gate 4 (#1192): classify the request with the T1 router (temperature 0) and select the
            // executor tier. Null when tiering is disabled or the router is not wired — the request
            // then uses the default model (documented rollback). The router picks a MODEL only; it
            // never affects tool or permission gating (Permission lock).
            routingDecision = routeTier(message);
            ModelTier tier = routingDecision == null ? null : routingDecision.tier();

            // #778: gate tool selection by the subject's persisted session workflow state when they
            // have one; otherwise fall back to message-heuristic derivation (session-less callers).
            Optional<WorkflowState> persistedState = workflowStateService.resolveActiveState(username);
            ToolSelectionEngine.ToolSelectionResult selection = persistedState
                    .map(state -> toolSelectionEngine.selectRoleTools(
                            role, currentUserContext.permissionCodes(), message, state))
                    .orElseGet(() ->
                            toolSelectionEngine.selectRoleTools(role, currentUserContext.permissionCodes(), message));
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
            PosAssistant agent =
                    getOrCreateAgent(role, cacheKey, selection.roleTools(), selection.fallbackTools(), tier);
            // Gate 3 (G3.3): publish the caller so the dynamic OpenApiToolProvider
            // (running inside the
            // cached agent) resolves this request's permission-eligible tools; cleared
            // in finally.
            if (requestScopedUserContext != null) {
                requestScopedUserContext.set(currentUserContext, currentAuthorizationHeader());
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
            // #1193: read the per-request write-capability signal (recorded by OpenApiToolProvider
            // during agent.chat, before the finally-clear below) so the telemetry prompt layers
            // match what the per-request prompt supplier actually assembled.
            boolean writeCapableToolsPresent = currentWriteCapableToolsPresent();
            AssembledPrompt assembled = rolePromptResolver.assemble(role, ragScope, writeCapableToolsPresent);
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
                    null,
                    tierRoutingOf(routingDecision),
                    writeCapableToolsPresent);
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
                    exception.getClass().getSimpleName(),
                    tierRoutingOf(routingDecision),
                    currentWriteCapableToolsPresent());
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

    /** The caller's raw {@code Authorization} header from the current servlet request, if present. */
    private @Nullable String currentAuthorizationHeader() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getHeader("Authorization");
        }
        return null;
    }

    private PosAssistant buildAgent(@NonNull String role) {
        return buildAgent(role, toolRegistry.resolveDomainTools(role), toolSelectionEngine.fullFallbackTools(), null);
    }

    private PosAssistant buildAgent(
            @NonNull String role,
            @NonNull Collection<Object> roleTools,
            @NonNull Collection<Object> fallbackTools,
            @Nullable ModelTier tier) {
        long startNanos = System.nanoTime();
        List<Object> tools = sharedOrchestrationSupport.mergeTools(roleTools, fallbackTools);
        String ragScope = toolRegistry.resolveRagScopeForTools(tools);
        String promptName = SystemPromptDefaults.promptNameForRagScope(ragScope);

        // 2. Tier 2 retrieval pipeline: semantic + expanded + hybrid + re-ranking.
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
        // #1124 item 4: permission-gate the candidates BEFORE re-ranking to the final top-K, so the
        // top-K is chosen from docs the caller may actually see and a gated doc can neither leak nor
        // displace a visible one. This must run before the top-K cut, not after. Codes are read per
        // request from the thread-local caller context (the agent is cached per role, the caller is
        // not), fail-closed to public-only when absent. Broadening the master scope above makes this
        // gating load-bearing: without it, master-scope queries would surface gated domain docs.
        QueryDocumentRetriever permissionFilteredRetriever = permissionFiltered(hybridRetriever);
        QueryDocumentRetriever rerankedRetriever =
                new RerankedContentRetriever(permissionFilteredRetriever, TIER2_FINAL_TOP_K);
        QueryDocumentRetriever resilientContentRetriever =
                new ResilientContentRetriever(rerankedRetriever, "tier2-hybrid-reranked-retriever");

        // #1193 cache safety: the WRITE-GATE layer is applied per request (the supplier reads the
        // request-scoped write-capability signal, resolved the same way tools are), so a cached
        // agent can never bake a WRITE_GATE prompt into requests without write-capable tools.
        PosAssistant agent = new SpringAiPosAssistant(
                executorChatModel(tier),
                () -> rolePromptResolver
                        .assemble(role, ragScope, currentWriteCapableToolsPresent())
                        .text(),
                tools,
                resilientContentRetriever,
                this::chatMemoryFor,
                openApiToolProvider,
                answerResolutionLadder);
        LOGGER.debug(
                "Built MCP role agent role={} promptName={} ragScope={} tier={} toolNames={}",
                role,
                promptName,
                ragScope,
                tier,
                sharedOrchestrationSupport.toolNames(tools));
        LOGGER.info(
                "Built MCP role agent role={} promptName={} ragScope={} tier={} tools={} in {} ms",
                role,
                promptName,
                ragScope,
                tier,
                tools.size(),
                elapsedMs(startNanos));
        return agent;
    }

    /** The chat model serving {@code tier}; the default model when untiered or no resolver is wired. */
    private @NonNull ChatModel executorChatModel(@Nullable ModelTier tier) {
        if (tier == null || tieredChatModelResolver == null) {
            return chatModel;
        }
        return tieredChatModelResolver.chatModelFor(tier);
    }

    /**
     * Classifies the request via the Gate 4 T1 router. Returns null (default model, no tier key
     * segment) when tiering is disabled ({@code mcp.model.tiering-enabled=false} — the documented
     * rollback) or the router is not wired. Never throws: router failures safe-default inside
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

    private boolean currentWriteCapableToolsPresent() {
        return requestScopedUserContext != null && requestScopedUserContext.currentWriteCapableToolsPresent();
    }

    /**
     * Wraps a retriever so each retrieval is permission-gated with the <em>current request's</em>
     * caller codes (read from the thread-local {@link RequestScopedUserContext}, since the agent is
     * cached per role but the caller is per request). Fail-closed to public-only docs when no caller
     * is published. See {@link PermissionAwareMetadataFilter} for the per-doc visibility rules.
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

    private @NonNull PosAssistant getOrCreateAgent(
            @NonNull String role,
            @NonNull String toolCacheKey,
            @NonNull List<Object> roleTools,
            @NonNull List<Object> fallbackTools,
            @Nullable ModelTier tier) {
        return roleAgentCache.get(
                agentCacheKey(role, toolCacheKey, tier), ignored -> buildAgent(role, roleTools, fallbackTools, tier));
    }

    /**
     * Cache key for a role agent: {@code role::toolCacheKey[::tier]}. Gate 4 cache safety: the tier
     * is part of the key, so a T2-simple agent (small model) is never reused for a T2-complex
     * request or vice versa. Untiered agents (tiering disabled, or the legacy full-tool path) keep
     * the historical {@code role::toolCacheKey} shape.
     */
    private static @NonNull String agentCacheKey(
            @NonNull String role, @NonNull String toolCacheKey, @Nullable ModelTier tier) {
        String base = role + MEMORY_KEY_SEPARATOR + toolCacheKey;
        return tier == null ? base : base + MEMORY_KEY_SEPARATOR + tier.name();
    }

    /**
     * Evicts a user's conversation state and rate counter. Role agents remain cached.
     */
    @Override
    public void evict(@NonNull String userId) {
        chatMemoryCache.asMap().keySet().removeIf(key -> key.startsWith(userId + MEMORY_KEY_SEPARATOR));
        requestCountCache.invalidate(userId);
    }

    /**
     * Drops every cached role agent when runtime agent configuration changes (a system-prompt write
     * or an {@code mcp_tool_permission} grant/revoke), so the next request rebuilds against the
     * committed configuration instead of waiting out the TTL (#639). Runs after the publishing
     * transaction commits; {@code fallbackExecution} covers non-transactional publishers.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAgentConfigurationChanged(@NonNull AgentCacheInvalidationEvent event) {
        long cachedAgents = roleAgentCache.estimatedSize();
        roleAgentCache.invalidateAll();
        LOGGER.info(
                "Invalidated MCP role-agent cache cachedAgents={} source={} detail={}",
                cachedAgents,
                event.source(),
                event.detail());
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
                // demand via getOrCreateAgent (its key already varies with toolCacheKey).
                ToolSelectionEngine.ToolSelectionResult selection =
                        toolSelectionEngine.selectRoleTools(role, prebuildPermissionCodes(role), role);
                List<Object> selectedTools =
                        sharedOrchestrationSupport.mergeTools(selection.roleTools(), selection.fallbackTools());
                String warmCacheKey = sharedOrchestrationSupport.toolCacheKey(selectedTools);
                roleAgentCache.put(
                        agentCacheKey(role, warmCacheKey, warmTier),
                        buildAgent(role, selection.roleTools(), selection.fallbackTools(), warmTier));

                // Keep the legacy full key warm for direct role-level access paths.
                roleAgentCache.put(role + MEMORY_KEY_SEPARATOR + FULL_TOOL_CACHE_KEY, buildAgent(role));
                prebuilt++;
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to prebuild MCP role agent role={}", role, exception);
            }
        }
        LOGGER.info("Prebuilt {} MCP role agents in {} ms", prebuilt, elapsedMs(startNanos));
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
        String response = ChatResponseText.extract(chatModel
                .call(simpleChatFastPath.prompt(currentUserContext, message))
                .getResult()
                .getOutput());
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
        emitChatTelemetry(
                currentUserContext, List.of(), List.of(), true, null, null, elapsedMs, "SUCCESS", null, null, false);
        return response;
    }

    /**
     * Emits one {@code nlti.request.telemetry} event for a completed chat request (Gate 1).
     * Never throws into the request path — a telemetry failure is logged and swallowed. No-op
     * when no emitter is configured. {@code correlationId} is taken from the request MDC when
     * present.
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
            @Nullable String errorCode,
            @Nullable TierRouting tierRouting,
            boolean writeCapableToolsPresent) {
        if (telemetryEmitter == null) {
            return;
        }
        try {
            String correlationId = MDC.get("correlationId");
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = UUID.randomUUID().toString();
            }
            List<String> discoveredOpenapiTools = requestScopedUserContext != null
                    ? requestScopedUserContext.currentDiscoveredOpenapiToolNames()
                    : List.of();
            telemetryEmitter.emit(NltiRequestTelemetryFactory.forChatRequest(
                    correlationId,
                    Instant.now(clock).toString(),
                    currentUserContext.primaryRole(),
                    currentUserContext.permissionCodes().size(),
                    selectedToolNames,
                    discoveredOpenapiTools,
                    promptLayers,
                    simpleChat,
                    simpleChatRule,
                    workflowState,
                    totalMs,
                    status,
                    errorCode,
                    tierRouting,
                    writeCapableToolsPresent));
        } catch (RuntimeException telemetryFailure) {
            LOGGER.warn(
                    "MCP telemetry emission failed role={} status={}",
                    currentUserContext.primaryRole(),
                    status,
                    telemetryFailure);
        }
    }

    private @NonNull String formatUserContext(@NonNull CurrentUserContext currentUserContext) {
        return sharedOrchestrationSupport.formatUserContext(currentUserContext);
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
