package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.github.benmanes.caffeine.cache.Cache;
import com.positivity.mcp.internal.classification.SimpleChatRuleDefaults;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.config.TieredChatModelResolver;
import com.positivity.mcp.internal.domain.ModelTier;
import com.positivity.mcp.internal.domain.RequestComplexity;
import com.positivity.mcp.internal.domain.RouterClassification;
import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.orchestration.rag.ScopedContentRetrieverFactory;
import com.positivity.mcp.internal.service.NltiRouter;
import com.positivity.mcp.internal.service.NltiWorkflowStateService;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.RolePromptResolver;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry;
import com.positivity.mcp.internal.telemetry.NltiTelemetryEmitter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Gate 4 (#1192) manager wiring: tier-keyed agent caching (a T2-simple agent is
 * never reused for a
 * T2-complex request), the tiering feature flag (disabled = legacy keys +
 * default model), router
 * decision + model names in telemetry, and the WRITE-GATE per-request
 * propagation (#1193) with its
 * write telemetry signal.
 */
@ExtendWith(MockitoExtension.class)
class SessionAgentManagerTieringTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000001192");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-07T02:00:00Z"), ZoneOffset.UTC);
    private static final String SIMPLE_MESSAGE = "show inventory stock";
    private static final String COMPLEX_MESSAGE = "post the invoice batch to the ledger";

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private PgVectorStore embeddingStore;

    @Mock
    private MasterAgentRegistry toolRegistry;

    @Mock
    private RolePromptResolver rolePromptResolver;

    @Mock
    private ToolSelectionEngine toolSelectionEngine;

    @Mock
    private ScopedContentRetrieverFactory scopedContentRetrieverFactory;

    @Mock
    private NltiTelemetryEmitter telemetryEmitter;

    @Mock
    private NltiWorkflowStateService workflowStateService;

    @Mock
    private NltiRouter nltiRouter;

    private ChatModel chatModel;
    private SharedOrchestrationSupport sharedOrchestrationSupport;
    private SimpleChatFastPath simpleChatFastPath;
    private RequestScopedUserContext requestScopedUserContext;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class, withSettings().extraInterfaces(StreamingChatModel.class));
        lenient()
                .when(chatModel.getOptions())
                .thenReturn(OllamaChatOptions.builder()
                        .model("gpt-oss:120b")
                        .temperature(0.2d)
                        .build());
        lenient()
                .when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        when(toolRegistry.preloadableRoleIdentifiers()).thenReturn(Set.of());
        lenient().when(toolRegistry.resolveDomainTools(anyString())).thenAnswer(inv -> new ArrayList<>());
        lenient().when(toolRegistry.resolveRagScopeForTools(anyCollection())).thenReturn("master");
        lenient()
                .when(toolSelectionEngine.selectRoleTools(anyString(), anySet(), anyString()))
                .thenReturn(new ToolSelectionEngine.ToolSelectionResult(List.of(), List.of()));
        lenient().when(toolSelectionEngine.fullFallbackTools()).thenReturn(List.of());
        lenient().when(workflowStateService.resolveActiveState(anyString())).thenReturn(Optional.empty());
        lenient()
                .when(rolePromptResolver.assemble(anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    boolean writeCapable = invocation.getArgument(2);
                    return writeCapable
                            ? new RolePromptResolver.AssembledPrompt("prompt", List.of("BASE", "WRITE_GATE"))
                            : new RolePromptResolver.AssembledPrompt("prompt", List.of("BASE"));
                });
        lenient().when(rolePromptResolver.resolvePrompt(anyString())).thenReturn("Default role prompt");
        QueryDocumentRetriever scopedRetriever = mock(QueryDocumentRetriever.class);
        lenient().when(scopedRetriever.retrieve(anyString())).thenReturn(List.of());
        lenient()
                .when(scopedContentRetrieverFactory.create(anyString(), anyInt(), anyDouble()))
                .thenReturn(scopedRetriever);
        sharedOrchestrationSupport = new SharedOrchestrationSupport(Clock.systemUTC());
        simpleChatFastPath = new SimpleChatFastPath(
                new SimpleChatClassifier(SimpleChatRuleDefaults.defaultCatalog()),
                rolePromptResolver,
                sharedOrchestrationSupport);
        requestScopedUserContext = new RequestScopedUserContext();
        requestScopedUserContext.clear();
    }

    @AfterEach
    void cleanup() {
        requestScopedUserContext.clear();
    }

    @Test
    @DisplayName("cache key carries the tier: a T2-simple agent is never reused for a T2-complex request")
    void tierKeyedCaching_separatesSimpleAndComplexAgents() {
        when(nltiRouter.classify(SIMPLE_MESSAGE)).thenReturn(decision(NltiIntentType.QUERY, ModelTier.T2_SIMPLE));
        when(nltiRouter.classify(COMPLEX_MESSAGE)).thenReturn(decision(NltiIntentType.ACTION, ModelTier.T2_COMPLEX));
        SessionAgentManager manager = buildManager(true, nltiRouter, null, null, null);

        manager.chat(userContext("user-1"), SIMPLE_MESSAGE);
        manager.chat(userContext("user-1"), COMPLEX_MESSAGE);

        Set<String> keys = roleAgentCacheKeys(manager);
        assertThat(keys).contains("ROLE_ADMIN::none::T2_SIMPLE").contains("ROLE_ADMIN::none::T2_COMPLEX");
    }

    @Test
    @DisplayName("mcp.model.tiering-enabled=false: router never consulted, legacy cache key, default model")
    void tieringDisabled_skipsRouterAndKeepsLegacyKey() {
        SessionAgentManager manager = buildManager(false, nltiRouter, null, null, null);

        manager.chat(userContext("user-1"), SIMPLE_MESSAGE);

        verify(nltiRouter, never()).classify(anyString());
        assertThat(roleAgentCacheKeys(manager)).contains("ROLE_ADMIN::none");
    }

    @Test
    @DisplayName("telemetry carries the router decision, selected tier, and actual model names")
    void telemetryCarriesRouterDecisionAndModelNames() {
        when(nltiRouter.classify(SIMPLE_MESSAGE)).thenReturn(decision(NltiIntentType.QUERY, ModelTier.T2_SIMPLE));
        TieredChatModelResolver resolver =
                new TieredChatModelResolver(chatModel, (StreamingChatModel) chatModel, "qwen3:4b", "qwen3:8b", "");
        SessionAgentManager manager = buildManager(true, nltiRouter, resolver, null, null);

        manager.chat(userContext("user-1"), SIMPLE_MESSAGE);

        NltiRequestTelemetry event = capturedEvent();
        assertThat(event.routing()).isNotNull();
        assertThat(event.routing().intentType()).isEqualTo("QUERY");
        assertThat(event.routing().riskLevel()).isEqualTo("LOW");
        assertThat(event.routing().domain()).isEqualTo("customer");
        assertThat(event.routing().complexity()).isEqualTo("SINGLE_LOOKUP");
        assertThat(event.routing().tier()).isEqualTo(NltiRequestTelemetry.Tier.T2_SIMPLE);
        assertThat(event.model()).isNotNull();
        assertThat(event.model().tierModel()).isEqualTo("qwen3:8b");
        assertThat(event.model().routerModel()).isEqualTo("qwen3:4b");
        assertThat(event.model().fallbackUsed()).isFalse();
    }

    @Test
    @DisplayName("#1193: write-capable openapi candidates flip the per-request WRITE-GATE flag and write telemetry;"
            + " the same cached agent serves a non-write request without the layer")
    void writeGateFlagIsPerRequest_neverBakedIntoCachedAgent() {
        when(nltiRouter.classify(anyString())).thenReturn(decision(NltiIntentType.QUERY, ModelTier.T2_SIMPLE));
        OpenApiToolProvider openApiToolProvider = mock(OpenApiToolProvider.class);
        // First request resolves a write-capable discovered op; second resolves none.
        when(openApiToolProvider.resolveToolCallbacks(anyString()))
                .thenAnswer(invocation -> {
                    requestScopedUserContext.recordWriteCapableToolsPresent(true);
                    return List.of();
                })
                .thenAnswer(invocation -> {
                    requestScopedUserContext.recordWriteCapableToolsPresent(false);
                    return List.of();
                });
        SessionAgentManager manager =
                buildManager(true, nltiRouter, null, openApiToolProvider, requestScopedUserContext);

        manager.chat(userContext("user-1"), SIMPLE_MESSAGE);
        manager.chat(userContext("user-1"), SIMPLE_MESSAGE);

        // Both requests hit one and the same cached (tier-keyed) agent.
        assertThat(roleAgentCacheKeys(manager)).containsExactly("ROLE_ADMIN::none::T2_SIMPLE");
        // The per-request prompt supplier called the 3-arg overload with the request's
        // own flag.
        verify(rolePromptResolver, atLeastOnce()).assemble(anyString(), eq("master"), eq(true));
        verify(rolePromptResolver, atLeastOnce()).assemble(anyString(), eq("master"), eq(false));

        ArgumentCaptor<NltiRequestTelemetry> eventCaptor = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter, org.mockito.Mockito.times(2)).emit(eventCaptor.capture());
        NltiRequestTelemetry writeEvent = eventCaptor.getAllValues().get(0);
        NltiRequestTelemetry readEvent = eventCaptor.getAllValues().get(1);
        assertThat(writeEvent.write()).isNotNull();
        assertThat(writeEvent.write().isWrite()).isTrue();
        assertThat(writeEvent.rag()).isNotNull();
        assertThat(writeEvent.rag().promptLayers()).contains(NltiRequestTelemetry.PromptLayer.WRITE_GATE);
        assertThat(readEvent.write()).isNull();
        assertThat(readEvent.rag().promptLayers()).doesNotContain(NltiRequestTelemetry.PromptLayer.WRITE_GATE);
        // The manager cleared the request-scoped holder after each request.
        assertThat(requestScopedUserContext.currentWriteCapableToolsPresent()).isFalse();
    }

    private NltiRouter.RoutingDecision decision(NltiIntentType intent, ModelTier tier) {
        NltiRiskLevel risk = tier == ModelTier.T2_COMPLEX ? NltiRiskLevel.HIGH : NltiRiskLevel.LOW;
        RequestComplexity complexity =
                tier == ModelTier.T2_COMPLEX ? RequestComplexity.MULTI_DOMAIN : RequestComplexity.SINGLE_LOOKUP;
        return new NltiRouter.RoutingDecision(new RouterClassification(intent, risk, complexity, "customer"), tier);
    }

    private NltiRequestTelemetry capturedEvent() {
        ArgumentCaptor<NltiRequestTelemetry> eventCaptor = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter).emit(eventCaptor.capture());
        return eventCaptor.getValue();
    }

    private SessionAgentManager buildManager(
            boolean tieringEnabled,
            NltiRouter router,
            TieredChatModelResolver resolver,
            OpenApiToolProvider openApiToolProvider,
            RequestScopedUserContext contextOrNull) {
        return new SessionAgentManager(
                chatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                sharedOrchestrationSupport,
                toolSelectionEngine,
                scopedContentRetrieverFactory,
                null, // toolExecutionAuditLogger
                null, // sessionSummary
                rolePromptResolver,
                simpleChatFastPath,
                telemetryEmitter,
                openApiToolProvider,
                null, // answerResolutionLadder
                contextOrNull,
                null, // roleDefaultPermissionsClient
                null, // toolInvocationRecorder
                workflowStateService,
                router,
                resolver,
                tieringEnabled,
                FIXED_CLOCK,
                30,
                500,
                50,
                100,
                0.6,
                0.55);
    }

    private static CurrentUserContext userContext(String username) {
        return new CurrentUserContext(
                username,
                USER_ID,
                "ROLE_ADMIN",
                Set.of("ROLE_ADMIN"),
                Set.of("ROLE_ADMIN", "mcp:chat:execute"),
                Set.of("AUTHENTICATED", "mcp:chat:execute"));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> roleAgentCacheKeys(SessionAgentManager sessionAgentManager) {
        Cache<String, ?> cache = (Cache<String, ?>) ReflectionTestUtils.getField(sessionAgentManager, "roleAgentCache");
        assertThat(cache).isNotNull();
        cache.cleanUp();
        return cache.asMap().keySet();
    }
}
