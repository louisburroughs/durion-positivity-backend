package com.positivity.mcp.internal.orchestration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.positivity.mcp.internal.classification.SimpleChatRuleCatalog;
import com.positivity.mcp.internal.client.RoleDefaultPermissionsClient;
import com.positivity.mcp.internal.config.AgentOrchestrationService;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.config.ScopeGraphProperties.Consumer;
import com.positivity.mcp.internal.config.SessionAgentCacheMetrics;
import com.positivity.mcp.internal.config.TieredChatModelResolver;
import com.positivity.mcp.internal.domain.ChatOutcome;
import com.positivity.mcp.internal.domain.ModelTier;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.RagScope;
import com.positivity.mcp.internal.domain.TurnSummary;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.event.AgentCacheInvalidationEvent;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.orchestration.ScopeShadowSupport.ScopeOutcome;
import com.positivity.mcp.internal.orchestration.ScopeShadowSupport.ScopePublication;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.memory.SemanticChatMemoryStore;
import com.positivity.mcp.internal.orchestration.memory.SessionSummary;
import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.orchestration.rag.ScopedContentRetrieverFactory;
import com.positivity.mcp.internal.orchestration.retrieval.PermissionAwareMetadataFilter;
import com.positivity.mcp.internal.scopegraph.ScopeConsumers;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import com.positivity.mcp.internal.security.PermissionCodes;
import com.positivity.mcp.internal.service.AnswerResolutionLadder;
import com.positivity.mcp.internal.service.ConversationIds;
import com.positivity.mcp.internal.service.ConversationMemoryHistory;
import com.positivity.mcp.internal.service.NltiRouter;
import com.positivity.mcp.internal.service.NltiWorkflowStateService;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.RolePromptResolver;
import com.positivity.mcp.internal.service.RolePromptResolver.AssembledPrompt;
import com.positivity.mcp.internal.service.SystemPromptDefaults;
import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import com.positivity.mcp.internal.telemetry.FallbackUsage;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryFactory;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryFactory.TaggingSignal;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryFactory.TierRouting;
import com.positivity.mcp.internal.telemetry.NltiTelemetryEmitter;
import com.positivity.tenancy.TenantContext;
import io.micrometer.observation.ObservationRegistry;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** #1655: passed to the tool-calling ChatClient so advisor observations are emitted. */
    private final @Nullable ObservationRegistry observationRegistry;

    private final @Nullable RoleDefaultPermissionsClient roleDefaultPermissionsClient;
    private final @Nullable ToolInvocationRecorder toolInvocationRecorder;
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

    /** #2073: reloads a persisted conversation's memory window on a cache miss; absent → fresh memory. */
    private @Nullable ConversationMemoryHistory conversationMemoryHistory;

    /**
     * ADR-0069 §6: the consumer switch. A constructor argument, not setter-injected, because the
     * warm-up in the constructor already builds agents and the RAG consumer decides at build time
     * which scopes the retrievers cover (spec §2.10: the mode is read once at startup). Null in
     * hand-built constructions: nothing is enforced and the mode reported is {@code shadow}.
     */
    private final @Nullable ScopeConsumers scopeConsumers;

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
            @Value("${mcp.rag.tier2-min-score:0.40}") double ragTier2MinScore,
            @Nullable ScopeConsumers scopeConsumers) {
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
        this.scopeConsumers = scopeConsumers;
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
     * #2073 (anvil decision 7): wires the persisted-conversation history used to rehydrate a
     * conversation's memory after a restart, eviction or TTL expiry. Setter-injected and optional so
     * existing constructions (and profiles without persistence) keep the pre-#2073 fresh-memory
     * behaviour.
     */
    @Autowired(required = false)
    public void setConversationMemoryHistory(@Nullable ConversationMemoryHistory conversationMemoryHistory) {
        this.conversationMemoryHistory = conversationMemoryHistory;
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
        return chat(currentUserContext, message, null);
    }

    @Override
    public @NonNull String chat(
            @NonNull CurrentUserContext currentUserContext, @NonNull String message, @Nullable String conversationId) {
        return chatTurn(currentUserContext, message, conversationId, null).text();
    }

    /**
     * One chat turn plus its turn summary (#2075): the path taken ({@code SIMPLE_CHAT} or {@code
     * AGENT}), how the answer was resolved, the tools the model called and the latency from just
     * before {@code beginTurn} to the reply in hand (the eval trace's and telemetry's window;
     * segmentation and persistence excluded). {@code assistantMessageId} is stamped on the eval trace
     * so a rating can be joined to it.
     */
    @Override
    public @NonNull ChatOutcome chatTurn(
            @NonNull CurrentUserContext currentUserContext,
            @NonNull String message,
            @Nullable String conversationId,
            @Nullable UUID assistantMessageId) {
        String username = currentUserContext.username();
        String role = currentUserContext.primaryRole();
        // ADR-0062 plan WS6 (R-B6): conversation memory and the rate counter are keyed beneath the
        // bound tenant, so the same username in two tenants never shares a history or a budget.
        UUID tenantId = TenantContext.require();
        AtomicInteger requestCount = requestCountCache.get(actorKey(tenantId, username), key -> new AtomicInteger(0));
        if (requestCount.incrementAndGet() > rateLimitPerSession) {
            requestCount.decrementAndGet();
            LOGGER.warn("Rate limit exceeded for username={} userId={}", username, currentUserContext.userId());
            throw new RateLimitExceededException("Rate limit exceeded");
        }

        long startMs = System.currentTimeMillis();
        NltiRouter.RoutingDecision routingDecision = null;
        // ADR-0068 §1: null until the turn is tagged, so a failure before that emits no tagging block.
        QuestionTags tags = null;
        // #1691: a failover flag left by a request that emitted no telemetry must not be charged
        // to this one.
        FallbackUsage.consume();
        try {
            if (toolInvocationRecorder != null) {
                toolInvocationRecorder.beginTurn(currentUserContext, message);
                // A non-UUID (ephemeral #1735) key parses to null: that turn has no persisted conversation.
                toolInvocationRecorder.recordMessage(
                        ConversationIds.parseCanonical(conversationId), assistantMessageId);
            }
            // ADR-0068 §1: the one tagging call of the turn, ahead of the simple-chat decision, the
            // tier routing and the tool selection, which all read this record and nothing else.
            tags = toolSelectionEngine.tag(message);
            if (toolInvocationRecorder != null && !tags.isNone()) {
                toolInvocationRecorder.recordTags(tags);
            }
            boolean simpleChat = simpleChatFastPath.isSimpleChat(message, tags);
            if (toolInvocationRecorder != null) {
                toolInvocationRecorder.recordSimpleChat(simpleChat);
            }
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
                SimpleChatReply simple = simpleChat(currentUserContext, message, startMs, tags);
                PosAssistant.Reply reply = simple.reply();
                if (toolInvocationRecorder != null) {
                    toolInvocationRecorder.completeTurn(reply.text());
                }
                return new ChatOutcome(
                        reply.text(),
                        new TurnSummary(
                                TurnSummary.PATH_SIMPLE_CHAT,
                                reply.answerSource(),
                                reply.toolsCalled(),
                                simple.latencyMs()));
            }

            // Gate 4 (#1192): classify the request with the T1 router (temperature 0) and select the
            // executor tier. Null when tiering is disabled or the router is not wired — the request
            // then uses the default model (documented rollback). The router picks a MODEL only; it
            // never affects tool or permission gating (Permission lock).
            routingDecision = routeTier(message, tags);
            ModelTier tier = routingDecision == null ? null : routingDecision.tier();
            if (toolInvocationRecorder != null) {
                toolInvocationRecorder.recordRouting(
                        routingDecision == null
                                ? null
                                : routingDecision.classification().intentType().name(),
                        tier == null ? null : tier.name());
            }

            // #778: gate tool selection by the subject's persisted session workflow state when they
            // have one; otherwise the workflow_state tag decides (session-less callers, ADR-0068 §3.3).
            Optional<WorkflowState> persistedState = workflowStateService.resolveActiveState(username);
            QuestionTags turnTags = tags;
            ToolSelectionEngine.ToolSelectionResult selection = persistedState
                    .map(state -> toolSelectionEngine.selectRoleTools(
                            role, currentUserContext.permissionCodes(), message, state, turnTags))
                    .orElseGet(() -> toolSelectionEngine.selectRoleTools(
                            role, currentUserContext.permissionCodes(), message, turnTags));
            List<Object> selectedTools =
                    sharedOrchestrationSupport.mergeTools(selection.roleTools(), selection.fallbackTools());
            List<String> selectedToolNames = sharedOrchestrationSupport.toolNames(selectedTools);
            if (toolInvocationRecorder != null) {
                toolInvocationRecorder.recordWorkflowState(
                        selection.workflowState().name());
                toolInvocationRecorder.recordSelectedTools(selectedToolNames);
                // ADR-0069 §9: the scope resolved by the shared selection step, recorded where the
                // other selection stages are. Null in mode off: nothing is recorded.
                if (selection.scope() != null) {
                    toolInvocationRecorder.recordScope(selection.scope());
                }
            }
            // ADR-0069 §6/§7: the scope, the facades it added and the card it renders, published
            // for the consumers that run inside the cached agent. Rendered per request, never baked in.
            ScopePublication publication =
                    ScopeShadowSupport.publicationFor(selection, scopeConsumers, currentUserContext.permissionCodes());
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
                // #1675: tools that need the caller's own wording read it from here rather than
                // from a model-supplied copy, which arrives normalised with the preposition gone.
                requestScopedUserContext.recordUserMessage(message);
                // ADR-0068 §1: the tag record too, for the readers inside the cached agent.
                requestScopedUserContext.recordTags(tags);
                // ADR-0069 §5: published next to the caller, for the same window, and cleared by the
                // same clear() in the finally below.
                publication.publish(requestScopedUserContext);
            }
            long agentStartNanos = System.nanoTime();
            PosAssistant.Reply reply = agent.reply(
                    memoryKey(tenantId, username, role, conversationId),
                    message,
                    formatUserContext(currentUserContext));
            // One clock read for both, so the turn summary and the telemetry/audit share a window.
            long turnElapsedMs = System.currentTimeMillis() - startMs;
            int latencyMs = TurnSummary.clampLatency(turnElapsedMs);
            int elapsedMs = (int) turnElapsedMs;
            LOGGER.info(
                    "MCP agent chat completed role={} selectedTools={} modelElapsedMs={} totalElapsedMs={}",
                    role,
                    selectedTools.size(),
                    elapsedMs(agentStartNanos),
                    elapsedMs);
            if (toolExecutionAuditLogger != null) {
                toolExecutionAuditLogger.logToolExecution(null, username, true, false, elapsedMs, null);
            }
            String ragScope = toolRegistry.resolveRagScopeForTools(selectedTools);
            // #1193: read the per-request write-capability signal (recorded by OpenApiToolProvider
            // during agent.chat, before the finally-clear below) so the telemetry prompt layers
            // match what the per-request prompt supplier actually assembled.
            boolean writeCapableToolsPresent = currentWriteCapableToolsPresent();
            // ADR-0069 §6: what the consumers did, read before the finally-clear like the write signal.
            ScopeOutcome scopeOutcome = ScopeShadowSupport.recordOutcome(
                    selection.scope(), scopeConsumers, requestScopedUserContext, toolInvocationRecorder);
            AssembledPrompt assembled = rolePromptResolver.assemble(role, ragScope, writeCapableToolsPresent);
            List<String> promptLayers = publication.withCardLayer(assembled != null ? assembled.layers() : List.of());
            emitChatTelemetry(
                    currentUserContext,
                    selectedToolNames,
                    promptLayers,
                    false,
                    null,
                    selection.workflowState().name(),
                    elapsedMs,
                    "SUCCESS",
                    null,
                    tierRoutingOf(routingDecision),
                    writeCapableToolsPresent,
                    selection.scope(),
                    scopeOutcome,
                    tags);
            if (toolInvocationRecorder != null) {
                toolInvocationRecorder.completeTurn(reply.text());
            }
            return new ChatOutcome(
                    reply.text(),
                    new TurnSummary(TurnSummary.PATH_AGENT, reply.answerSource(), reply.toolsCalled(), latencyMs));
        } catch (RuntimeException exception) {
            if (toolInvocationRecorder != null) {
                toolInvocationRecorder.failTurn(exception);
            }
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
                    currentWriteCapableToolsPresent(),
                    null,
                    ScopeOutcome.NONE,
                    tags);
            throw new IllegalStateException(
                    "MCP chat failed role=%s elapsedMs=%d errorName=%s"
                            .formatted(role, elapsedMs, exception.getClass().getSimpleName()),
                    exception);
        } finally {
            if (requestScopedUserContext != null) {
                requestScopedUserContext.clear();
            }
            if (toolInvocationRecorder != null) {
                toolInvocationRecorder.clearTurn();
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
        // ADR-0069 §6: where the RAG consumer is in enforce, the retrievers are built over ALL scopes
        // (the master scope is the factory's unfiltered case) so a second domain's documents can be
        // fetched at all, and the request-scoped ScopeRagFilter below narrows per request. Where it
        // is not, this is exactly today's scoped construction and no hook is installed.
        boolean scopeRagEnforced =
                scopeConsumers != null && requestScopedUserContext != null && scopeConsumers.enforces(Consumer.RAG);
        String retrieverScope = scopeRagEnforced ? RagScope.MASTER : ragScope;

        // 2. Tier 2 retrieval pipeline: semantic + expanded + hybrid + re-ranking.
        QueryDocumentRetriever semanticRetriever =
                scopedContentRetrieverFactory.create(retrieverScope, 10, ragMinScore);
        QueryDocumentRetriever broadSemanticRetriever =
                scopedContentRetrieverFactory.create(retrieverScope, TIER2_RETRIEVAL_CANDIDATES, ragTier2MinScore);
        QueryDocumentRetriever expandedRetriever = new QueryExpansionContentRetriever(
                broadSemanticRetriever, TIER2_EXPANDED_QUERY_LIMIT, TIER2_RETRIEVAL_CANDIDATES);
        // #784: dense + query-expansion, plus the lexical (FTS) source when enabled. RRF fusion when
        // lexical is present; otherwise the original insertion-order merge, so the dense-only path is
        // byte-for-byte unchanged when the feature flag is off.
        List<QueryDocumentRetriever> hybridSources = new ArrayList<>(List.of(semanticRetriever, expandedRetriever));
        Optional<QueryDocumentRetriever> lexicalRetriever = scopedContentRetrieverFactory.createLexical(retrieverScope);
        lexicalRetriever.ifPresent(hybridSources::add);
        QueryDocumentRetriever hybridRetriever = lexicalRetriever.isPresent()
                ? HybridContentRetriever.reciprocalRankFusion(
                        hybridSources, TIER2_RETRIEVAL_CANDIDATES, scopedContentRetrieverFactory.rrfK())
                : new HybridContentRetriever(hybridSources, TIER2_RETRIEVAL_CANDIDATES);
        // ADR-0069 §6: the scope hook sits after fusion and before the top-K cut, beside the
        // permission filter, so the top-K is chosen from in-scope (and visible) survivors.
        QueryDocumentRetriever scopeFilteredRetriever = scopeRagEnforced
                ? new ScopeRagFilter(hybridRetriever, ragScope, requestScopedUserContext, scopeConsumers)
                : hybridRetriever;
        // #1124 item 4: permission-gate the candidates BEFORE re-ranking to the final top-K, so the
        // top-K is chosen from docs the caller may actually see and a gated doc can neither leak nor
        // displace a visible one. This must run before the top-K cut, not after. Codes are read per
        // request from the thread-local caller context (the agent is cached per role, the caller is
        // not), fail-closed to public-only when absent. Broadening the master scope above makes this
        // gating load-bearing: without it, master-scope queries would surface gated domain docs.
        QueryDocumentRetriever permissionFilteredRetriever = permissionFiltered(scopeFilteredRetriever);
        // ADR-0068 spec §2.6: the compound gate reads the turn's tags from the request-scoped holder
        // (the retriever is built per cached agent, the tags per turn).
        QueryDocumentRetriever rerankedRetriever = new RerankedContentRetriever(
                permissionFilteredRetriever,
                TIER2_FINAL_TOP_K,
                requestScopedUserContext == null ? QuestionTags::none : requestScopedUserContext::currentTags);
        // ADR-0069 §9: observes the final top-K for the scope trace and returns it untouched; a plain
        // call-through unless a scope was published for the request.
        QueryDocumentRetriever resilientContentRetriever = new ResilientContentRetriever(
                new ScopeRetrievalObserver(rerankedRetriever, requestScopedUserContext, toolInvocationRecorder),
                "tier2-hybrid-reranked-retriever");

        // #1193 cache safety: the WRITE-GATE layer is applied per request (the supplier reads the
        // request-scoped write-capability signal, resolved the same way tools are), so a cached
        // agent can never bake a WRITE_GATE prompt into requests without write-capable tools. The
        // ADR-0069 §7 scope card is appended the same way, as the final SCOPE_CARD layer, from the
        // card published for this request; a cached agent never bakes one in.
        PosAssistant agent = new SpringAiPosAssistant(
                executorChatModel(tier),
                () -> withScopeCard(rolePromptResolver
                        .assemble(role, ragScope, currentWriteCapableToolsPresent())
                        .text()),
                tools,
                resilientContentRetriever,
                this::chatMemoryFor,
                openApiToolProvider,
                answerResolutionLadder,
                toolInvocationRecorder,
                requestScopedUserContext,
                observationRegistry);
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
    private NltiRouter.@Nullable RoutingDecision routeTier(@NonNull String message, @NonNull QuestionTags tags) {
        if (!tieringEnabled || nltiRouter == null) {
            return null;
        }
        // ADR-0068 §7: the router receives the turn's tags (it maps them in Wave 2).
        return nltiRouter.classify(message, tags);
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

    /** ADR-0069 §7: the assembled prompt with this request's scope card as its final layer, if one was rendered. */
    private @NonNull String withScopeCard(@NonNull String assembledPrompt) {
        return ScopeShadowSupport.currentCard(requestScopedUserContext)
                .map(card -> assembledPrompt + "\n\n" + card)
                .orElse(assembledPrompt);
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
     * Evicts a user's conversation state and rate counter within the bound tenant. Role agents
     * remain cached.
     */
    @Override
    public void evict(@NonNull String username) {
        String actor = actorKey(TenantContext.require(), username);
        chatMemoryCache.asMap().keySet().removeIf(key -> key.startsWith(actor + MEMORY_KEY_SEPARATOR));
        requestCountCache.invalidate(actor);
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
                // ADR-0068 spec §2.5: warm-up does not tag. The role name is not a question, so
                // heuristic answers for it would be meaningless and a provider call would count as a turn.
                ToolSelectionEngine.ToolSelectionResult selection = toolSelectionEngine.selectRoleTools(
                        role, prebuildPermissionCodes(role), role, QuestionTags.none());
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

    // Sonar (java:S2583) misreads "raced != null" below as always true: its dataflow engine models
    // ConcurrentMap#putIfAbsent (chatMemoryCache.asMap()) as returning a non-null ChatMemory, but
    // the JDK contract returns the PREVIOUS mapping, i.e. null exactly when this call is the one
    // that inserted the key — a false positive, not a real invariant. The null branch is the case
    // this line exists for (#2073: first published wins), so it must stay.
    @SuppressWarnings("java:S2583")
    private @NonNull ChatMemory chatMemoryFor(@NonNull Object memoryId) {
        // Tier 3: Replace MessageWindowChatMemory with SemanticChatMemoryStore
        // for persistent semantic memory and session summarization
        String memoryKey = String.valueOf(memoryId);
        ChatMemory cached = chatMemoryCache.getIfPresent(memoryKey);
        if (cached != null) {
            return cached;
        }
        // #2073: built (and, for a persisted conversation, rehydrated from the database) outside the
        // cache's compute, so the blocking read never holds a Caffeine bin lock. Two concurrent misses
        // may both build one; the first published wins and the other is discarded unused.
        ChatMemory built = newChatMemory(memoryKey);
        ChatMemory raced = chatMemoryCache.asMap().putIfAbsent(memoryKey, built);
        return raced != null ? raced : built;
    }

    /**
     * A fresh memory for {@code memoryKey}, seeded from the persisted conversation when the key names
     * one (#2073): the cache only misses for a persisted conversation after a restart, an eviction or
     * the TTL, and the model should continue that conversation rather than start over. Keys without a
     * conversation id, and the deprecated non-UUID ephemeral ids, start empty as before.
     */
    private @NonNull ChatMemory newChatMemory(@NonNull String memoryKey) {
        SemanticChatMemoryStore memory = new SemanticChatMemoryStore(
                memoryMaxMessages, chatModel, embeddingModel, embeddingStore, sessionSummary);
        ConversationMemoryHistory history = conversationMemoryHistory;
        UUID conversationId = persistedConversationId(memoryKey);
        if (history != null && conversationId != null) {
            List<Message> window = history.recentTurns(conversationId, memoryMaxMessages);
            if (!window.isEmpty()) {
                memory.add(memoryKey, window);
                LOGGER.debug("Rehydrated chat memory conversationId={} messages={}", conversationId, window.size());
            }
        }
        return memory;
    }

    /**
     * The conversation id of a {@link #memoryKey} when it is a canonical UUID (a persisted
     * conversation), else {@code null}.
     */
    static @Nullable UUID persistedConversationId(@NonNull String memoryKey) {
        String[] parts = memoryKey.split(MEMORY_KEY_SEPARATOR, -1);
        if (parts.length != 4) {
            return null;
        }
        return ConversationIds.parseCanonical(parts[3]);
    }

    /**
     * #2073: drops every cached memory of {@code conversationId} within the bound tenant — across the
     * actors and roles that chatted in it — after the conversation is deleted or purged, so a later
     * turn cannot resurrect its content from the cache.
     */
    @Override
    public void evictConversation(@NonNull String conversationId) {
        String tenantPrefix = TenantContext.require() + MEMORY_KEY_SEPARATOR;
        String suffix = MEMORY_KEY_SEPARATOR + conversationId;
        chatMemoryCache.asMap().keySet().removeIf(key -> key.startsWith(tenantPrefix) && key.endsWith(suffix));
    }

    /**
     * The fast path's reply and its turn-summary latency, measured at the same point as the path's
     * {@code totalElapsedMs} (before the audit log and telemetry), like the agent path (#2075).
     */
    private record SimpleChatReply(PosAssistant.@NonNull Reply reply, int latencyMs) {}

    private @NonNull SimpleChatReply simpleChat(
            @NonNull CurrentUserContext currentUserContext,
            @NonNull String message,
            long requestStartMs,
            @NonNull QuestionTags tags) {
        long simpleStartNanos = System.nanoTime();
        ChatResponseText.Extracted extracted = ChatResponseText.extractDetailed(chatModel
                .call(simpleChatFastPath.prompt(currentUserContext, message))
                .getResult()
                .getOutput());
        String response = extracted.text();
        if (toolInvocationRecorder != null) {
            // Same field as the agent path (#1816): a simple-chat reply is direct content or a raw
            // non-content source, never re-rendered or laddered.
            toolInvocationRecorder.recordAnswerSource(extracted.source().name());
        }
        long turnElapsedMs = System.currentTimeMillis() - requestStartMs;
        int elapsedMs = (int) turnElapsedMs;
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
                currentUserContext,
                List.of(),
                List.of(),
                true,
                null,
                null,
                elapsedMs,
                "SUCCESS",
                null,
                null,
                false,
                // The fast path resolves no scope (ADR-0069).
                null,
                ScopeOutcome.NONE,
                tags);
        // The fast path offers no tools; its answer source is the raw extraction source (#1816).
        return new SimpleChatReply(
                new PosAssistant.Reply(response, extracted.source().name(), List.of()),
                TurnSummary.clampLatency(turnElapsedMs));
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
            boolean writeCapableToolsPresent,
            @Nullable ScopeSet scope,
            @NonNull ScopeOutcome scopeOutcome,
            @Nullable QuestionTags tags) {
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
                    writeCapableToolsPresent,
                    ScopeShadowSupport.telemetrySignal(
                            scope, ScopeShadowSupport.propertiesOf(scopeConsumers), scopeOutcome),
                    TaggingSignal.of(tags)));
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

    /** The actor beneath which memory and the rate counter live: {@code tenant::username} (ADR-0062 plan WS6). */
    static @NonNull String actorKey(@NonNull UUID tenantId, @NonNull String username) {
        return tenantId + MEMORY_KEY_SEPARATOR + username;
    }

    /**
     * The conversation this turn belongs to: {@code tenant::username::role[::conversationId]}.
     *
     * <p>The tenant leads (ADR-0062 plan WS6, R-B6): a username is unique within a tenant only, so
     * without it the same login in two tenants would share one history. A null or blank {@code
     * conversationId} keeps the pre-#1735 shape beneath that, so an existing caller's memory is
     * unchanged and a running conversation still accumulates. A supplied id partitions the memory
     * beneath the actor, which is what lets a caller ask independent questions without each one
     * inheriting the last eleven.
     */
    static @NonNull String memoryKey(
            @NonNull UUID tenantId, @NonNull String username, @NonNull String role, @Nullable String conversationId) {
        String base = actorKey(tenantId, username) + MEMORY_KEY_SEPARATOR + role;
        return conversationId == null || conversationId.isBlank() ? base : base + MEMORY_KEY_SEPARATOR + conversationId;
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
