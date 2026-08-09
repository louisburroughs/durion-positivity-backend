package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Cache;
import com.positivity.mcp.internal.classification.SimpleChatRuleDefaults;
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
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry;
import com.positivity.mcp.internal.telemetry.NltiTelemetryEmitter;
import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.RolePromptResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

/**
 * Gate 4 (#1192) streaming wiring: the shared T0 rule fast-path (Gate 2A divergence closed — the
 * streaming manager now short-circuits pure social chat exactly like the blocking manager) and
 * tier-keyed streaming agent caching.
 */
@ExtendWith(MockitoExtension.class)
class StreamingSessionAgentManagerTieringTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000002192");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-07T02:00:00Z"), ZoneOffset.UTC);
    private static final String BUSINESS_MESSAGE = "show inventory stock";

    @Mock
    private StreamingChatModel streamingChatModel;

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

    private SharedOrchestrationSupport sharedOrchestrationSupport;
    private SimpleChatFastPath simpleChatFastPath;

    @BeforeEach
    void setUp() {
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
                .thenReturn(new RolePromptResolver.AssembledPrompt("prompt", List.of("BASE")));
        lenient().when(rolePromptResolver.resolvePrompt(anyString())).thenReturn("Default role prompt");
        QueryDocumentRetriever scopedRetriever = mock(QueryDocumentRetriever.class);
        lenient().when(scopedRetriever.retrieve(anyString())).thenReturn(List.of());
        lenient()
                .when(scopedContentRetrieverFactory.create(anyString(), anyInt(), anyDouble()))
                .thenReturn(scopedRetriever);
        sharedOrchestrationSupport = new SharedOrchestrationSupport();
        simpleChatFastPath = new SimpleChatFastPath(
                new SimpleChatClassifier(SimpleChatRuleDefaults.defaultCatalog()),
                rolePromptResolver,
                sharedOrchestrationSupport);
    }

    @Test
    @DisplayName("Gate 2A closure: streaming greeting takes the shared T0 fast-path — no tool selection, T0 telemetry")
    void streamChat_greeting_takesSharedSimpleChatFastPath() {
        when(streamingChatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chatResponse("Hello"), chatResponse("!")));
        StreamingSessionAgentManager manager = buildManager(true, nltiRouter);

        List<String> tokens =
                manager.streamChat(userContext("user-1"), "hello").collectList().block();

        assertThat(tokens).containsExactly("Hello", "!");
        verify(toolSelectionEngine, never()).selectRoleTools(anyString(), anySet(), anyString());
        verify(nltiRouter, never()).classify(anyString());
        assertThat(roleAgentCacheKeys(manager)).isEmpty();

        ArgumentCaptor<NltiRequestTelemetry> eventCaptor = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter).emit(eventCaptor.capture());
        NltiRequestTelemetry event = eventCaptor.getValue();
        assertThat(event.routing()).isNotNull();
        assertThat(event.routing().tier()).isEqualTo(NltiRequestTelemetry.Tier.T0_RULE);
        assertThat(event.outcome().status()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("streaming cache key carries the tier: simple and complex agents are cached separately")
    void streamChat_tierKeyedCaching_separatesAgents() {
        when(nltiRouter.classify(BUSINESS_MESSAGE))
                .thenReturn(decision(NltiIntentType.QUERY, ModelTier.T2_SIMPLE))
                .thenReturn(decision(NltiIntentType.ACTION, ModelTier.T2_COMPLEX));
        StreamingSessionAgentManager manager = buildManager(true, nltiRouter);

        manager.streamChat(userContext("user-1"), BUSINESS_MESSAGE);
        manager.streamChat(userContext("user-1"), BUSINESS_MESSAGE);

        assertThat(roleAgentCacheKeys(manager))
                .contains("ROLE_ADMIN::none::T2_SIMPLE")
                .contains("ROLE_ADMIN::none::T2_COMPLEX");
    }

    @Test
    @DisplayName("tiering disabled: router never consulted, legacy streaming cache key")
    void streamChat_tieringDisabled_keepsLegacyKey() {
        StreamingSessionAgentManager manager = buildManager(false, nltiRouter);

        manager.streamChat(userContext("user-1"), BUSINESS_MESSAGE);

        verify(nltiRouter, never()).classify(anyString());
        assertThat(roleAgentCacheKeys(manager)).contains("ROLE_ADMIN::none");
    }

    private NltiRouter.RoutingDecision decision(NltiIntentType intent, ModelTier tier) {
        NltiRiskLevel risk = tier == ModelTier.T2_COMPLEX ? NltiRiskLevel.HIGH : NltiRiskLevel.LOW;
        RequestComplexity complexity =
                tier == ModelTier.T2_COMPLEX ? RequestComplexity.MULTI_DOMAIN : RequestComplexity.SINGLE_LOOKUP;
        return new NltiRouter.RoutingDecision(new RouterClassification(intent, risk, complexity, "inventory"), tier);
    }

    private StreamingSessionAgentManager buildManager(boolean tieringEnabled, NltiRouter router) {
        return new StreamingSessionAgentManager(
                streamingChatModel,
                toolRegistry,
                sharedOrchestrationSupport,
                toolSelectionEngine,
                scopedContentRetrieverFactory,
                rolePromptResolver,
                simpleChatFastPath,
                null, // toolExecutionAuditLogger
                telemetryEmitter,
                null, // openApiToolProvider
                null, // requestScopedUserContext
                null, // roleDefaultPermissionsClient
                workflowStateService,
                router,
                null, // tieredChatModelResolver
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
                Set.of("ROLE_ADMIN", "mcp:chat:stream"),
                Set.of("AUTHENTICATED", "mcp:chat:stream"));
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> roleAgentCacheKeys(StreamingSessionAgentManager manager) {
        Cache<String, ?> cache = (Cache<String, ?>) ReflectionTestUtils.getField(manager, "roleAgentCache");
        assertThat(cache).isNotNull();
        cache.cleanUp();
        return cache.asMap().keySet();
    }
}
