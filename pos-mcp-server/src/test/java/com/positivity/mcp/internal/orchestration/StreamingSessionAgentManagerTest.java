package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Cache;
import com.positivity.mcp.internal.classification.SimpleChatRuleDefaults;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.event.AgentCacheInvalidationEvent;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.orchestration.rag.ScopedContentRetrieverFactory;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.service.NltiWorkflowStateService;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.RolePromptResolver;
import com.positivity.mcp.internal.service.ToolRegistryService;
import com.positivity.mcp.internal.telemetry.NltiTelemetryEmitter;
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
        exaWebSearchTool = new ExaWebSearchTool(RestClient.builder(), "https://api.exa.ai", "", "auto", 5);
        inventoryFacadeTool = new InventoryFacadeTool(
                RestClient.builder(),
                "http://api-gateway",
                "/inventory/v1/inventory/stock/{sku}",
                "/inventory/v1/inventory/search?q={query}",
                "/inventory/v1/inventory/locations/{locationId}/stock");
        orderFacadeTool = new OrderFacadeTool(
                RestClient.builder(),
                "http://api-gateway",
                "/order/v1/orders/{orderId}",
                "/order/v1/orders/search?q={query}");
        sharedOrchestrationSupport = new SharedOrchestrationSupport();
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
                0.55);
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
        assertThat(roleAgentCacheKeys(selectorManager)).contains("ROLE_CASHIER::InventoryFacadeTool");
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
                .doesNotContain("ROLE_CASHIER::InventoryFacadeTool+OrderFacadeTool");
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
                .doesNotContain("ROLE_CASHIER::InventoryFacadeTool+OrderFacadeTool");
    }

    @Test
    @DisplayName("prebuild merges the full shared fallback tool set")
    void constructor_prebuildRoleAgents_mergesFullSharedFallbackTools() {
        SharedOrchestrationSupport sharedSupportSpy = spy(new SharedOrchestrationSupport());
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
                0.55);

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
        SharedOrchestrationSupport sharedSupportSpy = spy(new SharedOrchestrationSupport());
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
                0.37);

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
                .contains("ROLE_CASHIER::InventoryFacadeTool")
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
                .contains("ROLE_CASHIER::ExaWebSearchTool")
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
                0.55);
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
                0.55);
    }

    private ToolSelectionEngine realToolSelectionEngine() {
        return new ToolSelectionEngine(
                toolRegistry,
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
                0.55);

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
}
