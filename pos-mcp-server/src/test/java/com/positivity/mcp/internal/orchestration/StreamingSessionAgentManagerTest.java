package com.positivity.mcp.internal.orchestration;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Cache;
import com.positivity.mcp.internal.classification.SimpleChatRuleDefaults;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.ScopeGraphProperties.Consumer;
import com.positivity.mcp.internal.domain.EvalTurnTrace;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.event.AgentCacheInvalidationEvent;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.orchestration.rag.ScopedContentRetrieverFactory;
import com.positivity.mcp.internal.orchestration.tools.DateWindowFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.GlossaryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.repository.EvalTurnTraceRepository;
import com.positivity.mcp.internal.scopegraph.ScopeConsumers;
import com.positivity.mcp.internal.scopegraph.ScopeResolver;
import com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import com.positivity.mcp.internal.service.AlphaEvalTurnTraceRecorder;
import com.positivity.mcp.internal.service.NltiWorkflowStateService;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.RolePromptResolver;
import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import com.positivity.mcp.internal.service.ToolRegistryService;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry;
import com.positivity.mcp.internal.telemetry.NltiTelemetryEmitter;
import com.positivity.mcp.tenancy.BoundTenant;
import com.positivity.tenancy.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Member;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

/**
 * Unit tests for {@link StreamingSessionAgentManager}: cache behaviour and
 * eviction.
 *
 * <p>
 * The manager is {@code @Profile("alpha")} so it is constructed directly via
 * {@code new}, bypassing the Spring profile gate.
 *
 * <p>
 * A real {@link ExaWebSearchTool} instance (empty API key, never makes HTTP
 * calls) is used rather than a Mockito mock to avoid the assistant runtime's
 * "Duplicated definition for tool: webSearch" error caused by Mockito
 * subclasses
 * re-exposing the parent {@code @Tool} annotation.
 *
 * <p>
 * {@code AiServices.builder(StreamingPosAssistant.class).build()} creates a JDK
 * dynamic proxy at agent-build time without invoking the model, so all three
 * scenarios can verify cache hit/miss behaviour without triggering real LLM
 * calls.
 */
@ExtendWith(MockitoExtension.class)
@ExtendWith(BoundTenant.class)
class StreamingSessionAgentManagerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000302");
    private static final Set<String> PERMISSION_CODES = Set.of("AUTHENTICATED", "mcp:chat:stream");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-13T02:00:00Z"), ZoneOffset.UTC);

    /**
     * Must implement {@link ChatModel} too: the production beans do, and the streaming assistant now
     * requires it because tool execution runs through {@code ChatClient} (#1653).
     */
    @Mock(extraInterfaces = ChatModel.class)
    private StreamingChatModel streamingChatModel;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private PgVectorStore embeddingStore;

    @Mock
    private MasterAgentRegistry toolRegistry;

    @Mock
    private RolePromptResolver rolePromptResolver;

    @Mock
    private ToolRegistryService toolRegistryService;

    @Mock
    private ToolSelectionEngine toolSelectionEngine;

    @Mock
    private ScopedContentRetrieverFactory scopedContentRetrieverFactory;

    @Mock
    private NltiTelemetryEmitter telemetryEmitter;

    @Mock
    private NltiWorkflowStateService workflowStateService;

    // Real instance required to prevent @Tool duplicate registration
    private DateWindowFacadeTool dateWindowFacadeTool;
    private ExaWebSearchTool exaWebSearchTool;
    private InventoryFacadeTool inventoryFacadeTool;
    private OrderFacadeTool orderFacadeTool;
    private SharedOrchestrationSupport sharedOrchestrationSupport;
    private SimpleChatFastPath simpleChatFastPath;

    private StreamingSessionAgentManager manager;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> ArgumentCaptor<List<T>> listCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    @BeforeEach
    void setUp() {
        // Return a fresh mutable list each invocation so buildAgent mutations don't
        // bleed
        when(toolRegistry.resolveDomainTools(any())).thenAnswer(inv -> new ArrayList<>());
        when(toolRegistry.preloadableRoleIdentifiers()).thenReturn(Set.of("ROLE_CASHIER", "ROLE_MANAGER"));
        // #778: default to session-less so existing tests exercise the message-heuristic path.
        lenient().when(workflowStateService.resolveActiveState(any())).thenReturn(Optional.empty());
        lenient().when(rolePromptResolver.resolvePrompt(any())).thenReturn("Default role prompt");
        lenient()
                .when(rolePromptResolver.assemble(any(), any(), anyBoolean()))
                .thenReturn(new RolePromptResolver.AssembledPrompt("prompt", List.of("BASE", "ROLE")));
        lenient()
                .when(toolSelectionEngine.selectRoleTools(any(), any(), any()))
                .thenReturn(new ToolSelectionEngine.ToolSelectionResult(List.of(), List.of()));
        dateWindowFacadeTool = new DateWindowFacadeTool(Clock.systemUTC());
        exaWebSearchTool = new ExaWebSearchTool(RestClient.builder(), "https://api.exa.ai", "", "auto", 5);
        inventoryFacadeTool = new InventoryFacadeTool(
                RestClient.builder(),
                "http://api-gateway",
                "/inventory/v1/inventory/stock/{sku}",
                "/inventory/v1/inventory/search?q={query}",
                "/inventory/v1/inventory/locations/{locationId}/stock",
                "/inventory/v1/inventory/replenishment/policies");
        orderFacadeTool = new OrderFacadeTool(
                RestClient.builder(),
                "http://api-gateway",
                "/order/v1/orders/{orderId}",
                "/order/v1/orders/search?q={query}",
                "/order/v1/orders/purchase-orders",
                "/order/v1/orders/purchase-orders/{poId}",
                "/order/v1/orders/purchase-orders/summary");
        sharedOrchestrationSupport = new SharedOrchestrationSupport(Clock.systemUTC());
        simpleChatFastPath = new SimpleChatFastPath(
                new SimpleChatClassifier(SimpleChatRuleDefaults.defaultCatalog()),
                rolePromptResolver,
                sharedOrchestrationSupport);
        QueryDocumentRetriever scopedRetriever = mock(QueryDocumentRetriever.class);
        lenient().when(toolRegistry.sharedTools()).thenReturn(List.of());
        lenient()
                .when(toolSelectionEngine.fullFallbackTools())
                .thenReturn(List.of(exaWebSearchTool, inventoryFacadeTool, orderFacadeTool));
        lenient()
                .when(toolRegistry.resolveRagScopeForTools(anyCollection()))
                .thenAnswer(invocation -> ragScopeFor(invocation.getArgument(0)));
        lenient()
                .when(scopedContentRetrieverFactory.create(anyString(), anyInt(), anyDouble()))
                .thenReturn(scopedRetriever);
        manager = new StreamingSessionAgentManager(
                streamingChatModel,
                toolRegistry,
                sharedOrchestrationSupport,
                toolSelectionEngine,
                scopedContentRetrieverFactory,
                rolePromptResolver,
                simpleChatFastPath,
                null, // toolAuditService
                telemetryEmitter,
                null, // openApiToolProvider
                null, // requestScopedUserContext
                null, // observationRegistry (#1655)
                null, // roleDefaultPermissionsClient
                null, // toolInvocationRecorder
                workflowStateService,
                null, // nltiRouter
                null, // tieredChatModelResolver
                true, // tieringEnabled (no-op without a router)
                FIXED_CLOCK,
                30,
                500,
                50,
                100,
                0.6,
                0.55,
                null // scopeConsumers (ADR-0069)
                );
        clearInvocations(toolRegistry);
        clearInvocations(toolSelectionEngine);
        clearInvocations(scopedContentRetrieverFactory);
        clearInvocations(rolePromptResolver);
    }

    @Test
    @DisplayName("manager no longer retains tool registry service state")
    void manager_doesNotRetainToolRegistryServiceField() {
        assertThat(java.util.Arrays.stream(StreamingSessionAgentManager.class.getDeclaredFields())
                        .map(Member::getName)
                        .toList())
                .doesNotContain("toolRegistryService");
    }

    @Test
    @DisplayName("streamChat returns a non-null Flux")
    void streamChat_returnsNonNullFlux() {
        Flux<String> result = manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "hello");

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("simple-chat stream replaces harmony markup with the fallback (#1838)")
    void streamChat_simpleChat_replacesMarkupWithFallback() {
        when(streamingChatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(
                        streamedChunk("<|channel|>"), streamedChunk("analysis<|message|>"), streamedChunk("Hi there")));

        List<String> tokens = manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "hello")
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        assertThat(tokens).containsExactly(ChatResponseText.BLANK_RESPONSE_FALLBACK);
    }

    private static org.springframework.ai.chat.model.ChatResponse streamedChunk(String text) {
        return new org.springframework.ai.chat.model.ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage(text))));
    }

    @Test
    @DisplayName("streamChat reuses cached agent for same userId+role")
    void streamChat_cachesAgentForSameUser() {
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "show inventory stock");
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "show inventory stock");

        verify(toolRegistry, never()).resolveDomainTools("ROLE_CASHIER");
    }

    @Test
    @DisplayName("evict removes cached agent so next streamChat rebuilds it")
    void evict_removesFromCache() {
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "show inventory stock");
        manager.evict("user-1");
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "show inventory stock");

        verify(toolRegistry, never()).resolveDomainTools("ROLE_CASHIER");
    }

    @Test
    @DisplayName("streamChat rebuilds agent when role changes for same userId")
    void streamChat_roleChange_rebuildsAgent() {
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "show inventory stock");
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_MANAGER"), "show inventory stock");

        verify(toolRegistry, never()).resolveDomainTools("ROLE_CASHIER");
        verify(toolRegistry, never()).resolveDomainTools("ROLE_MANAGER");
    }

    @Test
    @DisplayName("streamChat skips fallback tools already resolved for the role")
    void streamChat_skipsDuplicateFallbackTool() {
        when(toolSelectionEngine.selectRoleTools(
                        "ROLE_DUPLICATE", PERMISSION_CODES, "search the internet for tire prices"))
                .thenReturn(new ToolSelectionEngine.ToolSelectionResult(
                        List.of(exaWebSearchTool), List.of(exaWebSearchTool)));

        Flux<String> result = manager.streamChat(
                userContext("user-with-role-tool", USER_ID, "ROLE_DUPLICATE"), "search the internet for tire prices");

        assertThat(result).isNotNull();
        assertThat(roleAgentCacheKeys(manager)).contains("ROLE_DUPLICATE::ExaWebSearchTool");
    }

    // --- Phase 3: semantic tool selection through ToolSelectionEngine ---

    @Test
    @DisplayName("streamChat uses shared workflow derivation and tool narrowing")
    void streamChat_semanticSelection_narrowsToolsWhenCandidatesReturned() {
        String message = "show me inventory levels";
        ToolSelectionEngine realToolSelectionEngine = realToolSelectionEngine();
        when(toolRegistry.resolveDomainTools("ROLE_CASHIER"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of(inventoryToolMetadata()));
        when(toolRegistry.resolveToolsByName(List.of("inventoryFacadeTool"))).thenReturn(List.of(inventoryFacadeTool));

        StreamingSessionAgentManager selectorManager = streamingManagerWithToolSelectionEngine(realToolSelectionEngine);
        clearInvocations(toolRegistryService);
        clearInvocations(scopedContentRetrieverFactory);

        Flux<String> result = selectorManager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), message);

        assertThat(result).isNotNull();
        ArgumentCaptor<ToolSelectionContext> contextCaptor = ArgumentCaptor.forClass(ToolSelectionContext.class);
        verify(toolRegistryService).resolveCandidateTools(contextCaptor.capture(), eq(3));
        assertThat(contextCaptor.getValue().workflowState()).isEqualTo("IDLE");
        assertThat(roleAgentCacheKeys(selectorManager))
                .contains("ROLE_CASHIER::GlossaryFacadeTool+InventoryFacadeTool");
    }

    @Test
    @DisplayName("streamChat threads the persisted non-IDLE session workflow state into tool selection (#778)")
    void streamChat_usesPersistedWorkflowState_whenSessionPresent() {
        String message = "create a purchase order for vendor acme";
        when(workflowStateService.resolveActiveState("user-1")).thenReturn(Optional.of(WorkflowState.CREATING_PO));
        when(toolSelectionEngine.selectRoleTools(
                        eq("ROLE_CASHIER"), eq(PERMISSION_CODES), eq(message), eq(WorkflowState.CREATING_PO)))
                .thenReturn(
                        new ToolSelectionEngine.ToolSelectionResult(List.of(), List.of(), WorkflowState.CREATING_PO));

        Flux<String> result = manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), message);

        assertThat(result).isNotNull();
        // The persisted session state (not a message heuristic) gates selection, via the 4-arg overload.
        verify(toolSelectionEngine)
                .selectRoleTools(eq("ROLE_CASHIER"), eq(PERMISSION_CODES), eq(message), eq(WorkflowState.CREATING_PO));
        verify(toolSelectionEngine, never()).selectRoleTools("ROLE_CASHIER", PERMISSION_CODES, message);
    }

    @Test
    @DisplayName("streamChat uses shared tool selection even when registry service is unavailable")
    void streamChat_withoutRegistryService_usesSharedSelectionPath() {
        String message = "latest internet sales report";
        when(toolSelectionEngine.selectRoleTools("ROLE_CASHIER", PERMISSION_CODES, message))
                .thenReturn(new ToolSelectionEngine.ToolSelectionResult(
                        List.of(orderFacadeTool), List.of(exaWebSearchTool, inventoryFacadeTool)));

        Flux<String> result = manager.streamChat(userContext("user-shared-path", USER_ID, "ROLE_CASHIER"), message);

        assertThat(result).isNotNull();
        verify(toolSelectionEngine).selectRoleTools("ROLE_CASHIER", PERMISSION_CODES, message);
        assertThat(roleAgentCacheKeys(manager))
                .contains("ROLE_CASHIER::ExaWebSearchTool+InventoryFacadeTool+OrderFacadeTool");
    }

    @Test
    @DisplayName("streamChat yields NO role tools when the gated set is empty — fail closed (#1606)")
    void streamChat_semanticSelection_failsClosedOnEmptyResult() {
        String message = "show sales orders";
        ToolSelectionEngine realToolSelectionEngine = realToolSelectionEngine();
        when(toolRegistry.resolveDomainTools("ROLE_CASHIER"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of());

        StreamingSessionAgentManager selectorManager = streamingManagerWithToolSelectionEngine(realToolSelectionEngine);
        clearInvocations(toolRegistryService);
        clearInvocations(scopedContentRetrieverFactory);

        Flux<String> result = selectorManager.streamChat(userContext("user-2", USER_ID, "ROLE_CASHIER"), message);

        assertThat(result).isNotNull();
        verify(toolRegistryService).resolveCandidateTools(any(ToolSelectionContext.class), eq(3));
        // #1606/#1608: fail closed — the ungated domain set is no longer substituted.
        assertThat(roleAgentCacheKeys(selectorManager))
                .doesNotContain("ROLE_CASHIER::GlossaryFacadeTool+InventoryFacadeTool+OrderFacadeTool");
    }

    @Test
    @DisplayName("streamChat yields NO role tools when the gating query throws — fail closed (#1608)")
    void streamChat_semanticSelection_failsClosedOnException() {
        ToolSelectionEngine realToolSelectionEngine = realToolSelectionEngine();
        when(toolRegistry.resolveDomainTools("ROLE_CASHIER"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenThrow(new IllegalStateException("selector unavailable"));

        StreamingSessionAgentManager selectorManager = streamingManagerWithToolSelectionEngine(realToolSelectionEngine);
        clearInvocations(toolRegistryService);
        clearInvocations(scopedContentRetrieverFactory);

        Flux<String> result =
                selectorManager.streamChat(userContext("user-3", USER_ID, "ROLE_CASHIER"), "show sales orders");

        assertThat(result).isNotNull();
        verify(toolRegistryService).resolveCandidateTools(any(ToolSelectionContext.class), eq(3));
        // #1606/#1608: fail closed — the ungated domain set is no longer substituted.
        assertThat(roleAgentCacheKeys(selectorManager))
                .doesNotContain("ROLE_CASHIER::GlossaryFacadeTool+InventoryFacadeTool+OrderFacadeTool");
    }

    @Test
    @DisplayName("prebuild merges the full shared fallback tool set")
    void constructor_prebuildRoleAgents_mergesFullSharedFallbackTools() {
        SharedOrchestrationSupport sharedSupportSpy = spy(new SharedOrchestrationSupport(Clock.systemUTC()));
        when(toolRegistry.resolveDomainTools("ROLE_CASHIER")).thenReturn(new ArrayList<>());
        when(toolRegistry.preloadableRoleIdentifiers()).thenReturn(Set.of("ROLE_CASHIER"));

        new StreamingSessionAgentManager(
                streamingChatModel,
                toolRegistry,
                sharedSupportSpy,
                toolSelectionEngine,
                scopedContentRetrieverFactory,
                rolePromptResolver,
                simpleChatFastPath,
                null,
                telemetryEmitter,
                null, // openApiToolProvider
                null, // requestScopedUserContext
                null, // observationRegistry (#1655)
                null, // roleDefaultPermissionsClient
                null, // toolInvocationRecorder
                workflowStateService,
                null, // nltiRouter
                null, // tieredChatModelResolver
                true, // tieringEnabled (no-op without a router)
                FIXED_CLOCK,
                30,
                500,
                50,
                100,
                0.6,
                0.55,
                null // scopeConsumers (ADR-0069)
                );

        ArgumentCaptor<List<Object>> fallbackToolsCaptor = listCaptor();
        verify(sharedSupportSpy, atLeastOnce()).mergeTools(argThat(Collection::isEmpty), fallbackToolsCaptor.capture());
        assertThat(fallbackToolsCaptor.getAllValues())
                .anySatisfy(fallbackTools -> assertThat(fallbackTools)
                        .containsExactly(exaWebSearchTool, inventoryFacadeTool, orderFacadeTool));
        verify(scopedContentRetrieverFactory, atLeastOnce()).create("master", 10, 0.6);
        verify(scopedContentRetrieverFactory, atLeastOnce()).create("master", 20, 0.55);
    }

    @Test
    @DisplayName("#1194: configured similarity floors propagate to the dense retrievers")
    void constructor_customRagFloors_propagateToRetrieverFactory() {
        SharedOrchestrationSupport sharedSupportSpy = spy(new SharedOrchestrationSupport(Clock.systemUTC()));
        when(toolRegistry.resolveDomainTools("ROLE_CASHIER")).thenReturn(new ArrayList<>());
        when(toolRegistry.preloadableRoleIdentifiers()).thenReturn(Set.of("ROLE_CASHIER"));

        new StreamingSessionAgentManager(
                streamingChatModel,
                toolRegistry,
                sharedSupportSpy,
                toolSelectionEngine,
                scopedContentRetrieverFactory,
                rolePromptResolver,
                simpleChatFastPath,
                null,
                telemetryEmitter,
                null, // openApiToolProvider
                null, // requestScopedUserContext
                null, // observationRegistry (#1655)
                null, // roleDefaultPermissionsClient
                null, // toolInvocationRecorder
                workflowStateService,
                null, // nltiRouter
                null, // tieredChatModelResolver
                true, // tieringEnabled (no-op without a router)
                FIXED_CLOCK,
                30,
                500,
                50,
                100,
                0.42,
                0.37,
                null // scopeConsumers (ADR-0069)
                );

        verify(scopedContentRetrieverFactory, atLeastOnce()).create("master", 10, 0.42);
        verify(scopedContentRetrieverFactory, atLeastOnce()).create("master", 20, 0.37);
    }

    @Test
    @DisplayName("streamChat applies shared inventory fallback selection")
    void streamChat_withInventoryKeyword_includesInventoryFallbackTool() {
        ToolSelectionEngine realToolSelectionEngine = realToolSelectionEngine();
        when(toolRegistry.resolveDomainTools("ROLE_CASHIER")).thenReturn(new ArrayList<>());
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of());

        StreamingSessionAgentManager selectorManager = streamingManagerWithToolSelectionEngine(realToolSelectionEngine);

        selectorManager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "stock part 1234");

        assertThat(roleAgentCacheKeys(selectorManager))
                .contains("ROLE_CASHIER::GlossaryFacadeTool+InventoryFacadeTool")
                .contains("ROLE_CASHIER::full");
    }

    @Test
    @DisplayName("streamChat applies shared web fallback selection")
    void streamChat_withWebKeyword_includesExaFallbackTool() {
        ToolSelectionEngine realToolSelectionEngine = realToolSelectionEngine();
        when(toolRegistry.resolveDomainTools("ROLE_CASHIER")).thenReturn(new ArrayList<>());
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of());

        StreamingSessionAgentManager selectorManager = streamingManagerWithToolSelectionEngine(realToolSelectionEngine);

        selectorManager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "find latest internet news");

        assertThat(roleAgentCacheKeys(selectorManager))
                .contains("ROLE_CASHIER::ExaWebSearchTool+GlossaryFacadeTool")
                .contains("ROLE_CASHIER::full");
    }

    @Test
    @DisplayName("streamChat rebuilds agent after cache TTL expiry")
    void streamChat_rebuildsAgentAfterCacheTtlExpiry() {
        StreamingSessionAgentManager expiringManager = new StreamingSessionAgentManager(
                streamingChatModel,
                toolRegistry,
                sharedOrchestrationSupport,
                toolSelectionEngine,
                scopedContentRetrieverFactory,
                rolePromptResolver,
                simpleChatFastPath,
                null,
                telemetryEmitter,
                null, // openApiToolProvider
                null, // requestScopedUserContext
                null, // observationRegistry (#1655)
                null, // roleDefaultPermissionsClient
                null, // toolInvocationRecorder
                workflowStateService,
                null, // nltiRouter
                null, // tieredChatModelResolver
                true, // tieringEnabled (no-op without a router)
                FIXED_CLOCK,
                0,
                500,
                50,
                100,
                0.6,
                0.55,
                null // scopeConsumers (ADR-0069)
                );
        clearInvocations(toolRegistry);

        expiringManager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "show inventory stock");
        expiringManager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "show inventory stock");

        verify(toolSelectionEngine, times(3)).selectRoleTools(eq("ROLE_CASHIER"), any(), any());
    }

    @Test
    @DisplayName("onAgentConfigurationChanged empties the streaming role-agent cache")
    void onAgentConfigurationChanged_emptiesRoleAgentCache() {
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "show inventory stock");
        assertThat(roleAgentCacheKeys(manager)).isNotEmpty();

        manager.onAgentConfigurationChanged(AgentCacheInvalidationEvent.systemPromptChanged("ROLE_CASHIER"));

        assertThat(roleAgentCacheKeys(manager)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> roleAgentCacheKeys(StreamingSessionAgentManager selectorManager) {
        Cache<String, ?> cache = (Cache<String, ?>) ReflectionTestUtils.getField(selectorManager, "roleAgentCache");
        assertThat(cache).isNotNull();
        cache.cleanUp();
        return cache.asMap().keySet();
    }

    private StreamingSessionAgentManager streamingManagerWithToolSelectionEngine(ToolSelectionEngine selectionEngine) {
        return new StreamingSessionAgentManager(
                streamingChatModel,
                toolRegistry,
                sharedOrchestrationSupport,
                selectionEngine,
                scopedContentRetrieverFactory,
                rolePromptResolver,
                simpleChatFastPath,
                null,
                telemetryEmitter,
                null, // openApiToolProvider
                null, // requestScopedUserContext
                null, // observationRegistry (#1655)
                null, // roleDefaultPermissionsClient
                null, // toolInvocationRecorder
                workflowStateService,
                null, // nltiRouter
                null, // tieredChatModelResolver
                true, // tieringEnabled (no-op without a router)
                FIXED_CLOCK,
                30,
                500,
                50,
                100,
                0.6,
                0.55,
                null // scopeConsumers (ADR-0069)
                );
    }

    private ToolSelectionEngine realToolSelectionEngine() {
        return new ToolSelectionEngine(
                toolRegistry,
                dateWindowFacadeTool,
                new GlossaryFacadeTool(),
                exaWebSearchTool,
                inventoryFacadeTool,
                orderFacadeTool,
                toolRegistryService,
                sharedOrchestrationSupport,
                3);
    }

    private static ToolMetadata inventoryToolMetadata() {
        return new ToolMetadata(
                UUID.randomUUID(),
                "inventoryFacadeTool",
                "Inventory",
                "Inventory availability",
                "inventory",
                1.0,
                "low",
                200,
                true,
                "inventoryFacadeTool");
    }

    private static String ragScopeFor(java.util.Collection<?> tools) {
        boolean hasInventory = tools.stream().anyMatch(InventoryFacadeTool.class::isInstance);
        boolean hasOrder = tools.stream().anyMatch(OrderFacadeTool.class::isInstance);
        if (hasInventory && !hasOrder) {
            return "inventory";
        }
        if (hasOrder && !hasInventory) {
            return "orders";
        }
        return "master";
    }

    @Test
    @DisplayName("streamChat with an openapi provider wired builds and leaves the request context clean")
    void streamChat_withOpenApiProvider_doesNotLeakContext() {
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        OpenApiToolProvider openApiToolProvider = mock(OpenApiToolProvider.class);
        StreamingSessionAgentManager providerManager = new StreamingSessionAgentManager(
                streamingChatModel,
                toolRegistry,
                sharedOrchestrationSupport,
                toolSelectionEngine,
                scopedContentRetrieverFactory,
                rolePromptResolver,
                simpleChatFastPath,
                null,
                telemetryEmitter,
                openApiToolProvider,
                requestContext,
                null, // observationRegistry (#1655)
                null, // roleDefaultPermissionsClient
                null, // toolInvocationRecorder
                workflowStateService,
                null, // nltiRouter
                null, // tieredChatModelResolver
                true, // tieringEnabled (no-op without a router)
                FIXED_CLOCK,
                30,
                500,
                50,
                100,
                0.6,
                0.55,
                null // scopeConsumers (ADR-0069)
                );

        Flux<String> result =
                providerManager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "show open invoices");

        assertThat(result).isNotNull();
        // The caller is published/cleared only inside streamTokens (on subscribe), so building the
        // stream must not leave any caller in the request-scoped holder on this thread.
        assertThat(requestContext.current()).isEmpty();
    }

    private static CurrentUserContext userContext(String username, UUID userId, String primaryRole) {
        return new CurrentUserContext(
                username,
                userId,
                primaryRole,
                Set.of(primaryRole),
                Set.of(primaryRole, "mcp:chat:stream"),
                PERMISSION_CODES);
    }

    // ── #1850: a streamed turn writes an eval trace ─────────────────────────

    @Test
    @DisplayName("a streamed simple-chat turn opens, records and completes its eval turn (#1850)")
    void streamChat_recordsTheEvalTurnAcrossThreadHops() {
        ToolInvocationRecorder recorder = mock(ToolInvocationRecorder.class);
        // The manager binds the turn through runWithTurn; run the action so the recording lands.
        doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return null;
                })
                .when(recorder)
                .runWithTurn(any(), any());
        when(streamingChatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(streamedChunk("Hi "), streamedChunk("there"))
                        // A thread hop, which is what broke recording before this fix.
                        .publishOn(reactor.core.scheduler.Schedulers.boundedElastic()));
        StreamingSessionAgentManager tracingManager = managerWithRecorder(recorder);

        List<String> tokens = tracingManager
                .streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "hello")
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        assertThat(tokens).containsExactly("Hi ", "there");
        verify(recorder).beginTurn(any(), eq("hello"));
        verify(recorder).recordSimpleChat(true);
        verify(recorder, timeout(5_000)).completeTurn("Hi there");
        verify(recorder, never()).failTurn(any());
        // The request thread must not keep the turn bound for the next request on that thread.
        verify(recorder).clearTurn();
    }

    @Test
    @DisplayName("a streamed turn that fails records the failure on its trace rather than dropping it")
    void streamChat_recordsFailureOnTheTrace() {
        ToolInvocationRecorder recorder = mock(ToolInvocationRecorder.class);
        doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return null;
                })
                .when(recorder)
                .runWithTurn(any(), any());
        when(streamingChatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.error(new IllegalStateException("upstream gone")));
        StreamingSessionAgentManager tracingManager = managerWithRecorder(recorder);

        assertThatThrownBy(() -> tracingManager
                        .streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "hello")
                        .collectList()
                        .block(java.time.Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class);

        verify(recorder).beginTurn(any(), eq("hello"));
        verify(recorder, timeout(5_000)).failTurn(any(IllegalStateException.class));
        verify(recorder, never()).completeTurn(any());
    }

    @Test
    @DisplayName("a streamed audit write lands under the request's tenant when the stream completes on another thread")
    void streamChat_auditWriteCarriesTheRequestTenantAcrossAThreadHop() {
        // BoundTenant binds TENANT_A on this (request) thread only. The stream is published on
        // boundedElastic, so the completion callback that writes the tenant-scoped audit row runs
        // on a thread that never had the binding (ADR-0062 plan WS6).
        ToolExecutionAuditLogger auditLogger = mock(ToolExecutionAuditLogger.class);
        AtomicReference<Optional<UUID>> tenantAtWrite = new AtomicReference<>();
        AtomicReference<Thread> threadAtWrite = new AtomicReference<>();
        doAnswer(invocation -> {
                    tenantAtWrite.set(TenantContext.current());
                    threadAtWrite.set(Thread.currentThread());
                    return null;
                })
                .when(auditLogger)
                .logToolExecution(any(), anyString(), anyBoolean(), anyBoolean(), anyInt(), any());
        when(streamingChatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(streamedChunk("Hi "), streamedChunk("there"))
                        .publishOn(reactor.core.scheduler.Schedulers.boundedElastic()));

        List<String> tokens = managerWith(auditLogger, null)
                .streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "hello")
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        assertThat(tokens).containsExactly("Hi ", "there");
        verify(auditLogger, timeout(5_000)).logToolExecution(any(), eq("user-1"), eq(true), eq(false), anyInt(), any());
        assertThat(threadAtWrite.get())
                .as("the write happened off the request thread")
                .isNotSameAs(Thread.currentThread());
        assertThat(tenantAtWrite.get()).contains(TENANT_A);
        assertThat(TenantContext.current())
                .as("the request thread's binding is untouched")
                .contains(TENANT_A);
    }

    @Test
    @DisplayName("evict clears the actor's memory and rate entries within the bound tenant only")
    void evict_clearsTheActorsEntriesWithinTheTenant() {
        @SuppressWarnings("unchecked")
        com.github.benmanes.caffeine.cache.Cache<String, org.springframework.ai.chat.memory.ChatMemory> memory =
                (com.github.benmanes.caffeine.cache.Cache<String, org.springframework.ai.chat.memory.ChatMemory>)
                        org.springframework.test.util.ReflectionTestUtils.getField(manager, "chatMemoryCache");
        @SuppressWarnings("unchecked")
        com.github.benmanes.caffeine.cache.Cache<String, java.util.concurrent.atomic.AtomicInteger> counters =
                (com.github.benmanes.caffeine.cache.Cache<String, java.util.concurrent.atomic.AtomicInteger>)
                        org.springframework.test.util.ReflectionTestUtils.getField(manager, "requestCountCache");
        assertThat(memory).isNotNull();
        assertThat(counters).isNotNull();
        org.springframework.ai.chat.memory.ChatMemory chatMemory =
                mock(org.springframework.ai.chat.memory.ChatMemory.class);
        memory.put(StreamingSessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_CASHIER"), chatMemory);
        memory.put(StreamingSessionAgentManager.memoryKey(TENANT_A, "user-2", "ROLE_CASHIER"), chatMemory);
        memory.put(StreamingSessionAgentManager.memoryKey(TENANT_B, "user-1", "ROLE_CASHIER"), chatMemory);
        counters.put(
                StreamingSessionAgentManager.actorKey(TENANT_A, "user-1"),
                new java.util.concurrent.atomic.AtomicInteger(3));
        counters.put(
                StreamingSessionAgentManager.actorKey(TENANT_B, "user-1"),
                new java.util.concurrent.atomic.AtomicInteger(3));

        manager.evict("user-1");

        assertThat(memory.asMap().keySet())
                .containsExactlyInAnyOrder(
                        StreamingSessionAgentManager.memoryKey(TENANT_A, "user-2", "ROLE_CASHIER"),
                        StreamingSessionAgentManager.memoryKey(TENANT_B, "user-1", "ROLE_CASHIER"));
        assertThat(counters.asMap().keySet())
                .containsExactly(StreamingSessionAgentManager.actorKey(TENANT_B, "user-1"));
    }

    @Test
    @DisplayName("a real recorder writes a streamed turn's trace across a thread hop (#1850)")
    void streamChat_writesARealTraceAcrossAThreadHop() {
        // The mock-based tests above prove the calls happen; this one proves a trace is actually
        // persisted, with the turn bound on the Reactor thread that terminates the stream.
        EvalTurnTraceRepository traceRepository = mock(EvalTurnTraceRepository.class);
        AlphaEvalTurnTraceRecorder traceRecorder = new AlphaEvalTurnTraceRecorder(
                traceRepository, FIXED_CLOCK, java.time.Duration.ofHours(24), "sha-test");
        ToolInvocationRecorder recorder = new ToolInvocationRecorder(
                mock(com.positivity.mcp.internal.service.ToolAuditService.class),
                mock(com.positivity.mcp.internal.repository.ToolMetadataRepository.class),
                mock(com.positivity.mcp.internal.service.RequestScopedUserContext.class),
                traceRecorder);
        when(streamingChatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(streamedChunk("Hi "), streamedChunk("there"))
                        .publishOn(reactor.core.scheduler.Schedulers.boundedElastic()));

        List<String> tokens = managerWithRecorder(recorder)
                .streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "hello")
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        assertThat(tokens).containsExactly("Hi ", "there");
        // The turn is written on the Reactor thread that terminates the stream, which can be after
        // block() returns — so the assertion waits for it rather than racing it.
        ArgumentCaptor<EvalTurnTrace> saved = ArgumentCaptor.forClass(EvalTurnTrace.class);
        verify(traceRepository, timeout(5_000)).save(saved.capture());
        assertThat(saved.getValue().finalResponse()).isEqualTo("Hi there");
        assertThat(saved.getValue().userMessage()).isEqualTo("hello");
        assertThat(saved.getValue().error()).isNull();
    }

    @Test
    @DisplayName("a cancelled stream still records what was streamed rather than dropping the turn (#1850)")
    void streamChat_cancelledStreamStillRecordsTheTurn() {
        ToolInvocationRecorder recorder = recorderRunningBoundActions();
        when(streamingChatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(streamedChunk("one"), streamedChunk("two"), streamedChunk("three")));

        // reactor-test is not on this module's classpath, so cancel is driven directly: take(1)
        // consumes one element and cancels upstream, which is what a client disconnect does.
        List<String> received = managerWithRecorder(recorder)
                .streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "hello")
                .take(1)
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        assertThat(received).containsExactly("one");

        // A client disconnect or async timeout cancels the SSE stream; the turn happened and the
        // gate grades from traces, so it must not vanish.
        verify(recorder, timeout(5_000)).completeTurn(any());
    }

    @Test
    @DisplayName("a failure before the stream is assembled fails the turn and unbinds the thread (#1850)")
    void streamChat_failureBeforeAssemblyStillTerminatesTheTurn() {
        ToolInvocationRecorder recorder = recorderRunningBoundActions();
        when(toolSelectionEngine.selectRoleTools(any(), any(), any()))
                .thenThrow(new IllegalStateException("selection exploded"));

        assertThatThrownBy(() -> managerWithRecorder(recorder)
                        .streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "how many open work orders"))
                .isInstanceOf(IllegalStateException.class);

        verify(recorder).beginTurn(any(), eq("how many open work orders"));
        verify(recorder).failTurn(any(IllegalStateException.class));
        verify(recorder).clearTurn();
    }

    @Test
    @DisplayName("a streamed tool-path turn records the same stages the blocking path does (#1850)")
    void streamChat_toolPathRecordsRoutingWorkflowAndTools() {
        ToolInvocationRecorder recorder = recorderRunningBoundActions();
        when(((org.springframework.ai.chat.model.ChatModel) streamingChatModel).getOptions())
                .thenReturn(org.springframework.ai.ollama.api.OllamaChatOptions.builder()
                        .model("deepseek-v4-flash:0731")
                        .build());
        when(streamingChatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(streamedChunk("42 open")));

        managerWithRecorder(recorder)
                .streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "how many open work orders")
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        verify(recorder, timeout(5_000)).recordSimpleChat(false);
        verify(recorder, timeout(5_000)).recordWorkflowState(any());
        verify(recorder, timeout(5_000)).recordSelectedTools(any());
    }

    /** A recorder mock whose {@code runWithTurn} runs the bound action, as the real one does. */
    private static ToolInvocationRecorder recorderRunningBoundActions() {
        ToolInvocationRecorder recorder = mock(ToolInvocationRecorder.class);
        lenient()
                .doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return null;
                })
                .when(recorder)
                .runWithTurn(any(), any());
        return recorder;
    }

    /** The same manager the suite builds, with an eval-trace recorder wired in (#1850). */
    private StreamingSessionAgentManager managerWithRecorder(ToolInvocationRecorder recorder) {
        return managerWith(null, recorder);
    }

    private StreamingSessionAgentManager managerWith(
            @org.jspecify.annotations.Nullable ToolExecutionAuditLogger auditLogger,
            @org.jspecify.annotations.Nullable ToolInvocationRecorder recorder) {
        return new StreamingSessionAgentManager(
                streamingChatModel,
                toolRegistry,
                sharedOrchestrationSupport,
                toolSelectionEngine,
                scopedContentRetrieverFactory,
                rolePromptResolver,
                simpleChatFastPath,
                auditLogger,
                telemetryEmitter,
                null, // openApiToolProvider
                null, // requestScopedUserContext
                null, // observationRegistry
                null, // roleDefaultPermissionsClient
                recorder,
                workflowStateService,
                null, // nltiRouter
                null, // tieredChatModelResolver
                true,
                FIXED_CLOCK,
                30,
                500,
                50,
                100,
                0.6,
                0.55,
                null // scopeConsumers (ADR-0069)
                );
    }

    // ── ADR-0069 §5 / §9: transport parity with the blocking manager ────────

    private static final String SCOPE_MESSAGE = "is the work order WO-20391 in stock at the store?";

    private static ScopeSet scopeOf(String message) {
        return ScopeResolverFixtures.resolver(ScopeResolverFixtures.shadow(60), new SimpleMeterRegistry())
                .resolve(message, Set.of("AUTHENTICATED", ScopeResolverFixtures.WORKORDER_VIEW), WorkflowState.IDLE);
    }

    private StreamingSessionAgentManager scopeManager(
            ToolSelectionEngine selectionEngine,
            SharedOrchestrationSupport support,
            RequestScopedUserContext requestContext,
            ToolInvocationRecorder recorder,
            ScopeConsumers scopeConsumers) {
        return new StreamingSessionAgentManager(
                streamingChatModel,
                toolRegistry,
                support,
                selectionEngine,
                scopedContentRetrieverFactory,
                rolePromptResolver,
                simpleChatFastPath,
                null, // toolExecutionAuditLogger
                telemetryEmitter,
                null, // openApiToolProvider
                requestContext,
                null, // observationRegistry
                null, // roleDefaultPermissionsClient
                recorder,
                workflowStateService,
                null, // nltiRouter
                null, // tieredChatModelResolver
                true,
                FIXED_CLOCK,
                30,
                500,
                50,
                100,
                0.6,
                0.55,
                scopeConsumers);
    }

    /** What the agent saw in the request-scoped holder while it ran, and on which thread. */
    private record SeenByAgent(Optional<ScopeSet> scope, Optional<CurrentUserContext> caller, Thread thread) {}

    /** Seeds {@code target}'s cache with an agent that notes what is published while it assembles its stream. */
    private java.util.concurrent.atomic.AtomicReference<SeenByAgent> seedObservingAgent(
            StreamingSessionAgentManager target,
            RequestScopedUserContext requestContext,
            String role,
            Flux<String> tokens) {
        java.util.concurrent.atomic.AtomicReference<SeenByAgent> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        StreamingPosAssistant agent = (memoryId, userMessage, userContext) -> {
            seen.set(new SeenByAgent(requestContext.currentScope(), requestContext.current(), Thread.currentThread()));
            return tokens;
        };
        List<Object> selectedTools = sharedOrchestrationSupport.mergeTools(List.of(), List.of());
        String key = (String) ReflectionTestUtils.invokeMethod(
                target, "agentCacheKey", role, sharedOrchestrationSupport.toolCacheKey(selectedTools), null);
        @SuppressWarnings("unchecked")
        Cache<String, StreamingPosAssistant> cache =
                (Cache<String, StreamingPosAssistant>) ReflectionTestUtils.getField(target, "roleAgentCache");
        assertThat(cache).isNotNull();
        cache.put(key, agent);
        return seen;
    }

    @Test
    @DisplayName(
            "ADR-0069: a streamed turn records its scope with the selection stages, publishes it next to the caller on the subscribing thread, and clears it there")
    void streamChat_shadow_recordsPublishesAndClearsTheScopeAcrossTheThreadHop() throws Exception {
        ScopeSet scope = scopeOf(SCOPE_MESSAGE);
        when(toolSelectionEngine.selectRoleTools(any(), any(), any()))
                .thenReturn(
                        new ToolSelectionEngine.ToolSelectionResult(List.of(), List.of(), WorkflowState.IDLE, scope));
        ToolInvocationRecorder recorder = recorderRunningBoundActions();
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        StreamingSessionAgentManager scoped = scopeManager(
                toolSelectionEngine,
                sharedOrchestrationSupport,
                requestContext,
                recorder,
                ScopeResolverFixtures.consumers(ScopeResolverFixtures.shadow(60), new SimpleMeterRegistry()));
        java.util.concurrent.atomic.AtomicReference<SeenByAgent> seen =
                seedObservingAgent(scoped, requestContext, "ROLE_CASHIER", Flux.just("42 ", "open"));
        CurrentUserContext caller = userContext("user-1", USER_ID, "ROLE_CASHIER");
        // One dedicated thread stands in for the Reactor thread that subscribes the stream, so the
        // same thread can be asked afterwards what it still holds.
        java.util.concurrent.ExecutorService subscriber = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Flux<String> stream = scoped.streamChat(caller, SCOPE_MESSAGE);

            // Assembling the stream on the request thread publishes nothing there.
            assertThat(requestContext.currentScope()).isEmpty();
            assertThat(requestContext.current()).isEmpty();
            // Recorded on the request thread, where the other selection stages are recorded.
            org.mockito.InOrder stages = org.mockito.Mockito.inOrder(recorder);
            stages.verify(recorder).recordSelectedTools(any());
            stages.verify(recorder).recordScope(scope);

            List<String> tokens = stream.subscribeOn(reactor.core.scheduler.Schedulers.fromExecutor(subscriber))
                    .collectList()
                    .block(java.time.Duration.ofSeconds(5));

            assertThat(tokens).containsExactly("42 ", "open");
            // The scope crossed to the subscribing thread and was published next to the caller.
            assertThat(seen.get().thread()).isNotSameAs(Thread.currentThread());
            assertThat(seen.get().scope()).containsSame(scope);
            assertThat(seen.get().caller()).contains(caller);
            // Cleared with the caller, on that same thread, so a pooled thread cannot carry it on.
            assertThat(subscriber
                            .submit(() -> requestContext.currentScope().isPresent()
                                    || requestContext.current().isPresent())
                            .get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isFalse();
            assertThat(requestContext.currentScope()).isEmpty();
        } finally {
            subscriber.shutdownNow();
        }

        ArgumentCaptor<NltiRequestTelemetry> event = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter, timeout(5_000)).emit(event.capture());
        assertThat(event.getValue().schemaVersion()).isEqualTo(2);
        assertThat(event.getValue().scopeMode()).isEqualTo("SHADOW");
        assertThat(event.getValue().scopeGraphHash()).isEqualTo(scope.graphHash());
        assertThat(event.getValue().scopeConfidence()).isEqualTo("HIGH");
        assertThat(event.getValue().scopeEntityCount())
                .isEqualTo(scope.entities().size());
        assertThat(event.getValue().scopeToolCount()).isEqualTo(scope.tools().size());
        assertThat(event.getValue().scopeDocCount())
                .isEqualTo(scope.documentIds().size());
        assertThat(event.getValue().scopeAddedToolCount()).isZero();
        assertThat(event.getValue().scopeRagFilterApplied()).isFalse();
    }

    @Test
    @DisplayName(
            "ADR-0069: in mode off (no scope on the selection) a streamed turn records, publishes and reports none")
    void streamChat_off_recordsAndPublishesNoScope() {
        ToolInvocationRecorder recorder = recorderRunningBoundActions();
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        StreamingSessionAgentManager off = scopeManager(
                toolSelectionEngine,
                sharedOrchestrationSupport,
                requestContext,
                recorder,
                ScopeResolverFixtures.consumers(ScopeGraphProperties.off(), new SimpleMeterRegistry()));
        java.util.concurrent.atomic.AtomicReference<SeenByAgent> seen =
                seedObservingAgent(off, requestContext, "ROLE_CASHIER", Flux.just("ok"));

        off.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), SCOPE_MESSAGE)
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        assertThat(seen.get().caller()).isPresent();
        assertThat(seen.get().scope()).isEmpty();
        verify(recorder, never()).recordScope(any());
        ArgumentCaptor<NltiRequestTelemetry> event = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter, timeout(5_000)).emit(event.capture());
        assertThat(event.getValue().scopeMode()).isNull();
        assertThat(event.getValue().scopeGraphHash()).isNull();
        assertThat(event.getValue().scopeConfidence()).isNull();
        assertThat(event.getValue().scopeEntityCount()).isNull();
    }

    @Test
    @DisplayName("ADR-0069: a streamed turn that fails still clears the scope, and its ERROR telemetry carries none")
    void streamChat_failure_clearsTheScope() {
        ScopeSet scope = scopeOf(SCOPE_MESSAGE);
        when(toolSelectionEngine.selectRoleTools(any(), any(), any()))
                .thenReturn(
                        new ToolSelectionEngine.ToolSelectionResult(List.of(), List.of(), WorkflowState.IDLE, scope));
        ToolInvocationRecorder recorder = recorderRunningBoundActions();
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        StreamingSessionAgentManager scoped =
                scopeManager(toolSelectionEngine, sharedOrchestrationSupport, requestContext, recorder, null);
        java.util.concurrent.atomic.AtomicReference<SeenByAgent> seen = seedObservingAgent(
                scoped, requestContext, "ROLE_CASHIER", Flux.error(new IllegalStateException("model unavailable")));

        assertThatThrownBy(() -> scoped.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), SCOPE_MESSAGE)
                        .collectList()
                        .block(java.time.Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(seen.get().scope()).containsSame(scope);
        assertThat(requestContext.currentScope()).isEmpty();
        assertThat(requestContext.current()).isEmpty();
        verify(recorder).recordScope(scope);
        verify(recorder, timeout(5_000)).failTurn(any());
        ArgumentCaptor<NltiRequestTelemetry> event = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter, timeout(5_000)).emit(event.capture());
        assertThat(event.getValue().outcome().status()).isEqualTo("ERROR");
        assertThat(event.getValue().scopeMode()).isNull();
    }

    @Test
    @DisplayName("ADR-0069: the streamed simple-chat fast path resolves, records and publishes no scope")
    void streamChat_simpleChat_resolvesNoScope() {
        when(streamingChatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(streamedChunk("Hi")));
        ToolInvocationRecorder recorder = recorderRunningBoundActions();
        RequestScopedUserContext requestContext = spy(new RequestScopedUserContext());
        StreamingSessionAgentManager scoped = scopeManager(
                toolSelectionEngine,
                sharedOrchestrationSupport,
                requestContext,
                recorder,
                ScopeResolverFixtures.consumers(ScopeResolverFixtures.shadow(60), new SimpleMeterRegistry()));
        clearInvocations(toolSelectionEngine);

        scoped.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "hello")
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        verify(toolSelectionEngine, never()).selectRoleTools(any(), any(), any());
        verify(recorder, never()).recordScope(any());
        verify(requestContext, never()).recordScope(any());
        ArgumentCaptor<NltiRequestTelemetry> event = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter, timeout(5_000)).emit(event.capture());
        assertThat(event.getValue().scopeMode()).isNull();
    }

    /** One full streamed agent turn with a real selection engine; returns everything a scope could have changed. */
    private List<Object> observableTurn(ScopeResolver resolver) {
        return observableTurn(resolver, null);
    }

    /** As above, with a consumer switch wired into the engine and the manager. */
    private List<Object> observableTurn(ScopeResolver resolver, ScopeConsumers consumers) {
        clearInvocations(streamingChatModel, scopedContentRetrieverFactory, rolePromptResolver, toolRegistryService);
        SharedOrchestrationSupport fixedClockSupport = new SharedOrchestrationSupport(FIXED_CLOCK);
        ToolSelectionEngine engine = new ToolSelectionEngine(
                toolRegistry,
                dateWindowFacadeTool,
                new GlossaryFacadeTool(),
                exaWebSearchTool,
                inventoryFacadeTool,
                orderFacadeTool,
                toolRegistryService,
                fixedClockSupport,
                3);
        engine.setScopeResolver(resolver);
        engine.setScopeConsumers(consumers);
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        StreamingSessionAgentManager target = scopeManager(engine, fixedClockSupport, requestContext, null, consumers);
        clearInvocations(scopedContentRetrieverFactory, rolePromptResolver, toolRegistryService);

        List<String> tokens = target.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), SCOPE_MESSAGE)
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> prompts =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(streamingChatModel, atLeastOnce()).stream(prompts.capture());
        ArgumentCaptor<ToolSelectionContext> ranking = ArgumentCaptor.forClass(ToolSelectionContext.class);
        verify(toolRegistryService).resolveCandidateTools(ranking.capture(), eq(3));
        List<Object> observed = new ArrayList<>();
        observed.add(tokens);
        observed.add(roleAgentCacheKeys(target).stream().sorted().toList());
        observed.add(ranking.getValue());
        // The assembled prompt: every message the model received, and the tools it was offered.
        observed.add(prompts.getAllValues().stream()
                .map(org.springframework.ai.chat.prompt.Prompt::getContents)
                .toList());
        observed.add(prompts.getAllValues().stream()
                .map(prompt -> prompt.getInstructions().stream()
                        .map(message -> message.getMessageType() + ":" + message.getText())
                        .toList())
                .toList());
        observed.add(prompts.getAllValues().stream()
                .map(prompt -> prompt.getOptions()
                                instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
                        ? options.getToolCallbacks().stream()
                                .map(callback -> callback.getToolDefinition().name() + "|"
                                        + callback.getToolDefinition().inputSchema())
                                .toList()
                        : List.of())
                .toList());
        // The retrievers: which scopes, sizes and floors they were built with, and the prompt layers.
        observed.add(org.mockito.Mockito.mockingDetails(scopedContentRetrieverFactory).getInvocations().stream()
                .map(Object::toString)
                .toList());
        observed.add(org.mockito.Mockito.mockingDetails(rolePromptResolver).getInvocations().stream()
                .map(Object::toString)
                .toList());
        return observed;
    }

    @Test
    @DisplayName(
            "ADR-0069: in shadow, a streamed turn's tool selection, retrievers and assembled prompt are exactly what they are in off")
    void streamChat_shadow_isIdenticalToOff() {
        when(((org.springframework.ai.chat.model.ChatModel) streamingChatModel).getOptions())
                .thenReturn(org.springframework.ai.ollama.api.OllamaChatOptions.builder()
                        .model("test-model")
                        .build());
        when(toolRegistry.resolveDomainTools("ROLE_CASHIER"))
                .thenAnswer(invocation -> new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of(inventoryToolMetadata()));
        when(toolRegistry.resolveToolsByName(List.of("inventoryFacadeTool")))
                .thenAnswer(invocation -> new ArrayList<>(List.of(inventoryFacadeTool)));
        when(streamingChatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenAnswer(invocation -> Flux.just(streamedChunk("Stock found")));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        List<Object> off = observableTurn(null);
        List<Object> shadow = observableTurn(ScopeResolverFixtures.resolver(ScopeResolverFixtures.shadow(60), meters));

        // The scope really was resolved in the second turn, from a message that names an entity.
        assertThat(meters.get("mcp.scope.resolved")
                        .tag("confidence", "HIGH")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        // The comparison is over real content: prompts were captured, tools offered, retrievers built.
        assertThat(off.get(3).toString()).contains(SCOPE_MESSAGE);
        assertThat(off.get(5).toString()).contains("checkStock");
        assertThat(off.get(6).toString()).contains("scopedContentRetrieverFactory.create");
        assertThat(shadow).isEqualTo(off);
    }

    // ── ADR-0069 §6 / §7: the consumers on the streaming transport ──

    private static final String WORKORDER_ONLY_MESSAGE = "what is the status of work order WO-20391?";

    /** "ticket" denotes two entities (LOW); long enough to stay off the simple-chat fast path; names no other term. */
    private static final String LOW_MESSAGE =
            "can you please open the ticket for me and tell me everything that is going on with it right now";

    private static org.springframework.ai.document.Document chunk(String documentId, String ragScope, String text) {
        return new org.springframework.ai.document.Document(
                text, java.util.Map.of("document_id", documentId, "rag_scope", ragScope));
    }

    private void streamingModelAnswers(String text) {
        when(((org.springframework.ai.chat.model.ChatModel) streamingChatModel).getOptions())
                .thenReturn(org.springframework.ai.ollama.api.OllamaChatOptions.builder()
                        .model("test-model")
                        .build());
        when(streamingChatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenAnswer(invocation -> Flux.just(streamedChunk(text)));
    }

    /** A real engine over the fixture graph, wired to {@code consumers}, ranking to the order facade alone. */
    private ToolSelectionEngine orderRankingEngine(ScopeConsumers consumers, SimpleMeterRegistry meters) {
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of(new ToolMetadata(
                        UUID.randomUUID(),
                        "orderFacadeTool",
                        "Orders",
                        "Order lookup",
                        "orders",
                        1.0,
                        "low",
                        200,
                        true,
                        "orderFacadeTool")));
        when(toolRegistry.resolveToolsByName(List.of("orderFacadeTool")))
                .thenAnswer(invocation -> new ArrayList<>(List.of(orderFacadeTool)));
        ToolSelectionEngine engine = realToolSelectionEngine();
        engine.setScopeResolver(ScopeResolverFixtures.resolver(consumers.properties(), meters));
        engine.setScopeConsumers(consumers);
        return engine;
    }

    /** A bound-action recorder whose {@code wrap} hands the facade callbacks back unwrapped. */
    private static ToolInvocationRecorder recorderPassingCallbacks() {
        ToolInvocationRecorder recorder = recorderRunningBoundActions();
        lenient()
                .when(recorder.wrap(any(org.springframework.ai.tool.ToolCallback.class), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return recorder;
    }

    private List<String> streamedSystemPrompts() {
        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> prompts =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(streamingChatModel, atLeastOnce()).stream(prompts.capture());
        return prompts.getAllValues().stream()
                .map(prompt -> prompt.getSystemMessage().getText())
                .toList();
    }

    @Test
    @DisplayName(
            "ADR-0069 §9: a streamed turn in mode enforce with an empty consumer list is exactly shadow (and shadow is exactly off)")
    void streamChat_enforceWithoutConsumers_isIdenticalToShadow() {
        streamingModelAnswers("Stock found");
        when(toolRegistry.resolveDomainTools("ROLE_CASHIER"))
                .thenAnswer(invocation -> new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of(inventoryToolMetadata()));
        when(toolRegistry.resolveToolsByName(List.of("inventoryFacadeTool")))
                .thenAnswer(invocation -> new ArrayList<>(List.of(inventoryFacadeTool)));
        ScopeGraphProperties enforceNothing = ScopeResolverFixtures.enforce(60);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        List<Object> off = observableTurn(null);
        List<Object> shadow = observableTurn(ScopeResolverFixtures.resolver(ScopeResolverFixtures.shadow(60), meters));
        List<Object> enforce = observableTurn(
                ScopeResolverFixtures.resolver(enforceNothing, meters),
                ScopeResolverFixtures.consumers(enforceNothing, meters));

        assertThat(enforce).isEqualTo(shadow);
        assertThat(shadow).isEqualTo(off);
        assertThat(meters.find("mcp.scope.fallback").counters().stream()
                        .mapToDouble(counter -> counter.count())
                        .sum())
                .isZero();
    }

    @Test
    @DisplayName(
            "ADR-0069 §6 rag, streamed: all-scope retrievers, narrowed to the scope plus master before the top-K cut")
    void streamChat_ragEnforced_buildsAllScopeRetrieversAndNarrowsBeforeTheTopKCut() {
        streamingModelAnswers("In progress");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScopeConsumers consumers =
                ScopeResolverFixtures.consumers(ScopeResolverFixtures.enforce(60, Consumer.RAG), meters);
        List<org.springframework.ai.document.Document> pool = List.of(
                chunk("inventory.a", "inventory", "Inventory alpha"),
                chunk("inventory.b", "inventory", "Inventory beta"),
                chunk("inventory.c", "inventory", "Inventory gamma"),
                chunk("inventory.d", "inventory", "Inventory delta"),
                chunk("inventory.e", "inventory", "Inventory epsilon"),
                chunk("workorder.status-lifecycle", "workorder", "Workorder lifecycle states"),
                chunk("glossary", "master", "Glossary of terms"),
                chunk("orders.faq", "orders", "Orders FAQ"));
        when(scopedContentRetrieverFactory.create(anyString(), anyInt(), anyDouble()))
                .thenReturn(query -> pool);
        ToolInvocationRecorder recorder = recorderPassingCallbacks();
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        StreamingSessionAgentManager target = scopeManager(
                orderRankingEngine(consumers, meters), sharedOrchestrationSupport, requestContext, recorder, consumers);
        clearInvocations(scopedContentRetrieverFactory, telemetryEmitter);

        // ROLE_ADMIN is not warmed up, so the agent is built by this turn, after the mocks were cleared.
        List<String> tokens = target.streamChat(
                        new CurrentUserContext(
                                "user-1",
                                USER_ID,
                                "ROLE_ADMIN",
                                Set.of("ROLE_ADMIN"),
                                Set.of("ROLE_ADMIN"),
                                Set.of("AUTHENTICATED", ScopeResolverFixtures.WORKORDER_VIEW)),
                        WORKORDER_ONLY_MESSAGE)
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        assertThat(tokens).containsExactly("In progress");
        verify(scopedContentRetrieverFactory, org.mockito.Mockito.times(2)).create(eq("master"), anyInt(), anyDouble());
        verify(scopedContentRetrieverFactory).createLexical("master");
        verify(scopedContentRetrieverFactory, never()).create(eq("orders"), anyInt(), anyDouble());
        String systemPrompt = streamedSystemPrompts().getLast();
        assertThat(systemPrompt).contains("Workorder lifecycle states", "Glossary of terms");
        assertThat(systemPrompt).doesNotContain("Inventory", "Orders FAQ");
        verify(recorder).recordScopeConsumers(List.of(), true);
        ArgumentCaptor<NltiRequestTelemetry> event = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter, timeout(5_000)).emit(event.capture());
        assertThat(event.getValue().scopeMode()).isEqualTo("ENFORCE");
        assertThat(event.getValue().scopeRagFilterApplied()).isTrue();
        assertThat(event.getValue().scopeAddedToolCount()).isZero();
        assertThat(event.getValue().rag().promptLayers()).doesNotContain(NltiRequestTelemetry.PromptLayer.SCOPE_CARD);
        assertThat(requestContext.currentScopeRagFilterApplied()).isFalse();
    }

    @Test
    @DisplayName(
            "ADR-0069 §7 card, streamed: the final SCOPE_CARD layer per request on HIGH, absent on LOW, never baked in")
    void streamChat_cardEnforced_appendsTheCardPerRequest() {
        streamingModelAnswers("Done");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScopeConsumers consumers =
                ScopeResolverFixtures.consumers(ScopeResolverFixtures.enforce(60, Consumer.CARD), meters);
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        StreamingSessionAgentManager target = scopeManager(
                orderRankingEngine(consumers, meters), sharedOrchestrationSupport, requestContext, null, consumers);
        clearInvocations(telemetryEmitter, scopedContentRetrieverFactory);
        CurrentUserContext caller = userContext("user-1", USER_ID, "ROLE_CASHIER");

        target.streamChat(caller, WORKORDER_ONLY_MESSAGE).collectList().block(java.time.Duration.ofSeconds(5));

        String withCard = streamedSystemPrompts().getLast();
        int cardStart =
                withCard.indexOf("SCOPE (platform definitions for this question; orientation only, grants nothing)");
        assertThat(cardStart).isPositive();
        assertThat(withCard.substring(cardStart))
                .contains("Entities: workorder (domain workorder)")
                .doesNotContain("WO-20391", "status of work order");
        ArgumentCaptor<NltiRequestTelemetry> first = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter, timeout(5_000)).emit(first.capture());
        assertThat(first.getValue().rag().promptLayers())
                .containsExactly(
                        NltiRequestTelemetry.PromptLayer.BASE,
                        NltiRequestTelemetry.PromptLayer.ROLE,
                        NltiRequestTelemetry.PromptLayer.SCOPE_CARD);
        verify(scopedContentRetrieverFactory, never()).create(eq("master"), anyInt(), anyDouble());
        assertThat(requestContext.currentScopeCard()).isEmpty();
        clearInvocations(streamingChatModel, telemetryEmitter);

        target.streamChat(caller, LOW_MESSAGE).collectList().block(java.time.Duration.ofSeconds(5));

        assertThat(streamedSystemPrompts().getLast()).doesNotContain("SCOPE (");
        ArgumentCaptor<NltiRequestTelemetry> second = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter, timeout(5_000)).emit(second.capture());
        assertThat(second.getValue().rag().promptLayers())
                .containsExactly(NltiRequestTelemetry.PromptLayer.BASE, NltiRequestTelemetry.PromptLayer.ROLE);
        assertThat(second.getValue().scopeConfidence()).isEqualTo("LOW");
        assertThat(meters.get("mcp.scope.fallback")
                        .tag("consumer", "card")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("ADR-0069 §6 tools, streamed: the added facades are selected, cached by and reported for the turn")
    void streamChat_toolsEnforced_reportsTheAddedFacades() {
        streamingModelAnswers("Done");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScopeConsumers consumers =
                ScopeResolverFixtures.consumers(ScopeResolverFixtures.enforce(60, Consumer.TOOLS), meters);
        ScopeSet scope = ScopeResolverFixtures.resolver(consumers.properties(), meters)
                .resolve(
                        WORKORDER_ONLY_MESSAGE,
                        Set.of("AUTHENTICATED", ScopeResolverFixtures.WORKORDER_VIEW),
                        WorkflowState.IDLE);
        when(toolSelectionEngine.selectRoleTools(any(), any(), any()))
                .thenReturn(new ToolSelectionEngine.ToolSelectionResult(
                        List.of(orderFacadeTool, inventoryFacadeTool),
                        List.of(),
                        WorkflowState.IDLE,
                        scope,
                        List.of("InventoryFacadeTool")));
        ToolInvocationRecorder recorder = recorderPassingCallbacks();
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        StreamingSessionAgentManager target =
                scopeManager(toolSelectionEngine, sharedOrchestrationSupport, requestContext, recorder, consumers);
        clearInvocations(telemetryEmitter);

        target.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), WORKORDER_ONLY_MESSAGE)
                .collectList()
                .block(java.time.Duration.ofSeconds(5));

        verify(recorder).recordSelectedTools(List.of("OrderFacadeTool", "InventoryFacadeTool"));
        verify(recorder).recordScopeConsumers(List.of("InventoryFacadeTool"), false);
        ArgumentCaptor<NltiRequestTelemetry> event = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter, timeout(5_000)).emit(event.capture());
        assertThat(event.getValue().scopeAddedToolCount()).isEqualTo(1);
        assertThat(event.getValue().tools().selected()).containsExactly("OrderFacadeTool", "InventoryFacadeTool");
        assertThat(roleAgentCacheKeys(target)).contains("ROLE_CASHIER::InventoryFacadeTool+OrderFacadeTool");
        assertThat(meters.get("mcp.scope.fallback")
                        .tag("consumer", "tools")
                        .counter()
                        .count())
                .isZero();
        assertThat(requestContext.currentScopeAddedToolNames()).isEmpty();
    }
}
