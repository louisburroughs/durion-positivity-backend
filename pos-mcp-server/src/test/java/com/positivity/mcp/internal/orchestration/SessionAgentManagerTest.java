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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.positivity.mcp.internal.service.RolePromptResolver;
import com.positivity.mcp.internal.service.ToolRegistryService;
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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link SessionAgentManager}: cache behaviour and eviction.
 *
 * <p>
 * {@code SessionAgentManager} is annotated {@code @Profile("alpha")} so Spring
 * never
 * instantiates it during slice tests. Here we construct it directly via
 * {@code new},
 * bypassing the profile gate, and supply Mockito mocks for all assistant runtime
 * dependencies.
 *
 * <p>
 * {@code AiServices.builder(PosAssistant.class).build()} creates a JDK dynamic
 * proxy.
 * No model calls are made during proxy construction, so a real
 * {@link ExaWebSearchTool}
 * instance (empty API key, never makes HTTP calls) is used rather than a
 * Mockito mock.
 * Using a Mockito mock would trigger an assistant runtime "Duplicated definition for
 * tool: webSearch"
 * error because Mockito subclasses inherit and re-expose the parent's
 * {@code @Tool} annotation.
 */
@ExtendWith(MockitoExtension.class)
class SessionAgentManagerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000301");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-13T02:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ChatModel chatModel;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private PgVectorStore embeddingStore;

    @Mock
    private MasterAgentRegistry toolRegistry;

    @Mock
    private ToolRegistryService toolRegistryService;

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

    // Real instance required: Mockito subclasses cause @Tool duplicate registration
    // in the assistant runtime
    private ExaWebSearchTool exaWebSearchTool;
    private InventoryFacadeTool inventoryFacadeTool;
    private OrderFacadeTool orderFacadeTool;
    private SimpleChatClassifier simpleChatClassifier;
    private SimpleChatFastPath simpleChatFastPath;
    private SharedOrchestrationSupport sharedOrchestrationSupport;

    private SessionAgentManager manager;

    @BeforeEach
    void setUp() {
        // ChatClient (which runs the tool-execution loop) dereferences ChatModel.getOptions()
        // unconditionally, so a bare @Mock returning null options NPEs the whole chat path.
        // Real models always carry options; mirror that here.
        lenient()
                .when(chatModel.getOptions())
                .thenReturn(OllamaChatOptions.builder().model("test-model").build());
        // Return a FRESH list on every invocation so buildAgent mutations don't bleed
        // across calls
        lenient().when(toolRegistry.resolveDomainTools(anyString())).thenAnswer(inv -> new ArrayList<>());
        lenient().when(toolRegistry.resolveToolsByName(anyCollection())).thenAnswer(inv -> new ArrayList<>());
        when(toolRegistry.preloadableRoleIdentifiers()).thenReturn(Set.of("ROLE_CASHIER", "ROLE_MANAGER"));
        lenient()
                .when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), anyInt()))
                .thenReturn(List.of());
        lenient()
                .when(toolSelectionEngine.selectRoleTools(anyString(), anySet(), anyString()))
                .thenReturn(new ToolSelectionEngine.ToolSelectionResult(List.of(), List.of()));
        // #778: default to session-less so existing tests exercise the message-heuristic path.
        lenient().when(workflowStateService.resolveActiveState(anyString())).thenReturn(Optional.empty());
        lenient().when(rolePromptResolver.resolvePrompt(anyString())).thenReturn("Default role prompt");
        lenient()
                .when(rolePromptResolver.assemble(anyString(), anyString(), anyBoolean()))
                .thenReturn(new RolePromptResolver.AssembledPrompt("prompt", List.of("BASE", "ROLE")));
        lenient().when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f});
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
        simpleChatClassifier = new SimpleChatClassifier(SimpleChatRuleDefaults.defaultCatalog());
        sharedOrchestrationSupport = new SharedOrchestrationSupport(Clock.systemUTC());
        simpleChatFastPath =
                new SimpleChatFastPath(simpleChatClassifier, rolePromptResolver, sharedOrchestrationSupport);
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
        manager = new SessionAgentManager(
                chatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                sharedOrchestrationSupport,
                toolSelectionEngine,
                scopedContentRetrieverFactory,
                null,
                null, // sessionSummary
                rolePromptResolver,
                simpleChatFastPath,
                telemetryEmitter,
                null, // openApiToolProvider
                null, // answerResolutionLadder
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
                0.55);
        clearInvocations(toolRegistry);
        clearInvocations(toolRegistryService);
        clearInvocations(toolSelectionEngine);
        clearInvocations(scopedContentRetrieverFactory);
        clearInvocations(rolePromptResolver);
    }

    @Test
    @DisplayName("getOrCreateAgent returns the same proxy instance for the same userId")
    void getOrCreateAgent_returnsSameInstanceForSameUser() {
        PosAssistant first = manager.getOrCreateAgent("user-1", "TECH");
        PosAssistant second = manager.getOrCreateAgent("user-1", "TECH");

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("getOrCreateAgent reuses the prebuilt role proxy for different userIds")
    void getOrCreateAgent_reusesRoleProxyForDifferentUsers() {
        PosAssistant forUser1 = manager.getOrCreateAgent("user-1", "ROLE_CASHIER");
        PosAssistant forUser2 = manager.getOrCreateAgent("user-2", "ROLE_CASHIER");

        assertThat(forUser1).isSameAs(forUser2);
    }

    @Test
    @DisplayName("getOrCreateAgent rebuilds agent when role changes for same userId")
    void getOrCreateAgent_roleChange_rebuildsAgent() {
        PosAssistant original = manager.getOrCreateAgent("user-1", "TECH");
        PosAssistant afterRoleChange = manager.getOrCreateAgent("user-1", "MANAGER");

        assertThat(afterRoleChange).isNotSameAs(original);
    }

    @Test
    @DisplayName("getOrCreateAgent skips fallback tools already resolved for the role")
    void getOrCreateAgent_skipsDuplicateFallbackTool() {
        when(toolRegistry.resolveDomainTools("ROLE_DUPLICATE"))
                .thenAnswer(inv -> new ArrayList<>(List.of(inventoryFacadeTool)));

        PosAssistant agent = manager.getOrCreateAgent("user-with-role-tool", "ROLE_DUPLICATE");

        assertThat(agent).isNotNull();
    }

    @Test
    @DisplayName("evict leaves prebuilt role agent cached")
    void evict_leavesPrebuiltRoleAgentCached() {
        PosAssistant before = manager.getOrCreateAgent("user-1", "ROLE_CASHIER");
        manager.evict("user-1");

        @SuppressWarnings("unchecked")
        Cache<String, ?> cache = (Cache<String, ?>) ReflectionTestUtils.getField(manager, "roleAgentCache");
        assertThat(cache).isNotNull();
        cache.cleanUp();
        assertThat(cache.estimatedSize()).isPositive();

        PosAssistant after = manager.getOrCreateAgent("user-1", "ROLE_CASHIER");
        assertThat(after).isSameAs(before);
    }

    @Test
    @DisplayName("chat with greeting uses simple no-tool model path")
    void chat_withGreeting_usesSimpleModelPath() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Hello!"));

        String response = manager.chat(userContext("user-1", USER_ID, "ROLE_ADMIN"), "hello");

        assertThat(response).isEqualTo("Hello!");
        // Prompt resolution now happens deferred in systemMessageProvider lambda at runtime
        verify(toolSelectionEngine, never()).selectRoleTools(anyString(), anySet(), anyString());
        verify(toolRegistryService, never()).resolveCandidateTools(any(ToolSelectionContext.class), anyInt());
    }

    @Test
    @DisplayName("chat with business request uses shared workflow derivation and tool narrowing")
    void chat_withBusinessRequest_narrowsRoleToolsPerMessage() {
        String message = "show stock for sku ABC";
        ToolSelectionEngine realToolSelectionEngine = realToolSelectionEngine();
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of(inventoryToolMetadata()));
        when(toolRegistry.resolveToolsByName(List.of("inventoryFacadeTool"))).thenReturn(List.of(inventoryFacadeTool));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Stock found"));
        SessionAgentManager selectorManager = managerWithToolSelectionEngine(realToolSelectionEngine);
        clearInvocations(toolRegistryService);
        clearInvocations(scopedContentRetrieverFactory);

        String response = selectorManager.chat(userContext("user-1", USER_ID, "ROLE_ADMIN"), message);

        assertThat(response).isEqualTo("Stock found");
        // Prompt resolution now happens deferred in systemMessageProvider lambda at runtime
        ArgumentCaptor<ToolSelectionContext> contextCaptor = ArgumentCaptor.forClass(ToolSelectionContext.class);
        verify(toolRegistryService).resolveCandidateTools(contextCaptor.capture(), eq(3));
        verify(scopedContentRetrieverFactory).create("inventory", 10, 0.6);
        verify(scopedContentRetrieverFactory).create("inventory", 20, 0.55);
        assertThat(contextCaptor.getValue().workflowState()).isEqualTo("IDLE");
        assertThat(roleAgentCacheKeys(selectorManager)).contains("ROLE_ADMIN::InventoryFacadeTool");
    }

    @Test
    @DisplayName("chat emits one nlti.request.telemetry event with actor, tools, prompt layers, SUCCESS")
    void chat_emitsRequestTelemetry() {
        String message = "show stock for sku ABC";
        ToolSelectionEngine realToolSelectionEngine = realToolSelectionEngine();
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of(inventoryToolMetadata()));
        when(toolRegistry.resolveToolsByName(List.of("inventoryFacadeTool"))).thenReturn(List.of(inventoryFacadeTool));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Stock found"));
        SessionAgentManager selectorManager = managerWithToolSelectionEngine(realToolSelectionEngine);

        selectorManager.chat(userContext("user-1", USER_ID, "ROLE_ADMIN"), message);

        ArgumentCaptor<NltiRequestTelemetry> eventCaptor = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter).emit(eventCaptor.capture());
        NltiRequestTelemetry event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(NltiRequestTelemetry.EVENT_TYPE);
        assertThat(event.schemaVersion()).isEqualTo(NltiRequestTelemetry.SCHEMA_VERSION);
        assertThat(event.actor().primaryRole()).isEqualTo("ROLE_ADMIN");
        assertThat(event.outcome().status()).isEqualTo("SUCCESS");
        assertThat(event.tools()).isNotNull();
        assertThat(event.tools().selected()).contains("InventoryFacadeTool");
        assertThat(event.rag()).isNotNull();
        assertThat(event.rag().promptLayers())
                .containsExactly(NltiRequestTelemetry.PromptLayer.BASE, NltiRequestTelemetry.PromptLayer.ROLE);
        // Gate 2C: the resolved workflow state is surfaced (IDLE for this session-less lookup query).
        assertThat(event.routing()).isNotNull();
        assertThat(event.routing().workflowState()).isEqualTo("IDLE");
    }

    @Test
    @DisplayName("chat with empty shared selection gets NO role tools — fail closed (#1606)")
    void chat_withEmptySemanticSelection_failsClosed() {
        String message = "show stock for sku ABC";
        ToolSelectionEngine realToolSelectionEngine = realToolSelectionEngine();
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Stock found"));
        SessionAgentManager selectorManager = managerWithToolSelectionEngine(realToolSelectionEngine);
        clearInvocations(toolRegistryService);
        clearInvocations(scopedContentRetrieverFactory);

        String response = selectorManager.chat(userContext("user-1", USER_ID, "ROLE_ADMIN"), message);

        assertThat(response).isEqualTo("Stock found");
        // Prompt resolution now happens deferred in systemMessageProvider lambda at runtime
        verify(toolRegistryService).resolveCandidateTools(any(ToolSelectionContext.class), eq(3));
        // Second-order effect of failing closed, asserted rather than glossed: RAG scope is derived
        // from the resolved tool set, so emptying roleTools leaves only the keyword fallback
        // (inventory) and the scope narrows from "master" to "inventory" — less context, which is
        // consistent with a caller the gate authorised for nothing.
        verify(scopedContentRetrieverFactory).create("inventory", 10, 0.6);
        verify(scopedContentRetrieverFactory).create("inventory", 20, 0.55);
        // #1606: an empty gated set no longer substitutes the ungated domain tool set, so the
        // agent is built with no role tools and the cache key reflects that.
        assertThat(roleAgentCacheKeys(selectorManager))
                .doesNotContain("ROLE_ADMIN::InventoryFacadeTool+OrderFacadeTool");
    }

    @Test
    @DisplayName("chat threads the persisted non-IDLE session workflow state into tool selection (#778)")
    void chat_usesPersistedWorkflowState_whenSessionPresent() {
        String message = "create a purchase order for vendor acme";
        when(workflowStateService.resolveActiveState("user-1")).thenReturn(Optional.of(WorkflowState.CREATING_PO));
        when(toolSelectionEngine.selectRoleTools(anyString(), anySet(), anyString(), any(WorkflowState.class)))
                .thenReturn(
                        new ToolSelectionEngine.ToolSelectionResult(List.of(), List.of(), WorkflowState.CREATING_PO));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("ok"));

        manager.chat(userContext("user-1", USER_ID, "ROLE_ADMIN"), message);

        // The persisted session state (not a message heuristic) gates selection.
        verify(toolSelectionEngine)
                .selectRoleTools(
                        eq("ROLE_ADMIN"),
                        eq(Set.of("AUTHENTICATED", "mcp:chat:execute")),
                        eq(message),
                        eq(WorkflowState.CREATING_PO));
        verify(toolSelectionEngine, never()).selectRoleTools(anyString(), anySet(), anyString());
    }

    @Test
    @DisplayName("getOrCreateAgent rebuilds agent after cache TTL expiry")
    void getOrCreateAgent_rebuildsAgentAfterCacheTtlExpiry() {
        SessionAgentManager expiringManager = new SessionAgentManager(
                chatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                sharedOrchestrationSupport,
                toolSelectionEngine,
                scopedContentRetrieverFactory,
                null,
                null, // sessionSummary
                rolePromptResolver,
                simpleChatFastPath,
                telemetryEmitter,
                null, // openApiToolProvider
                null, // answerResolutionLadder
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
                0.55);
        clearInvocations(toolRegistry);

        expiringManager.getOrCreateAgent("user-1", "ROLE_CASHIER");
        expiringManager.getOrCreateAgent("user-1", "ROLE_CASHIER");

        verify(toolRegistry, times(2)).resolveDomainTools("ROLE_CASHIER");
    }

    @Test
    @DisplayName("onAgentConfigurationChanged evicts prebuilt role agents so the next request rebuilds")
    void onAgentConfigurationChanged_evictsCachedRoleAgents() {
        // Prebuilt at construction time — a warm request is served from cache without a rebuild.
        manager.getOrCreateAgent("user-1", "ROLE_CASHIER");
        verify(toolRegistry, never()).resolveDomainTools("ROLE_CASHIER");

        manager.onAgentConfigurationChanged(AgentCacheInvalidationEvent.systemPromptChanged("ROLE_CASHIER"));
        manager.getOrCreateAgent("user-1", "ROLE_CASHIER");

        verify(toolRegistry, times(1)).resolveDomainTools("ROLE_CASHIER");
    }

    @Test
    @DisplayName("onAgentConfigurationChanged also rebuilds after a tool-permission change")
    void onAgentConfigurationChanged_toolPermissionChange_evictsCachedRoleAgents() {
        manager.getOrCreateAgent("user-1", "ROLE_TECHNICIAN");

        manager.onAgentConfigurationChanged(AgentCacheInvalidationEvent.toolPermissionChanged("inventory_stockcheck"));
        manager.getOrCreateAgent("user-1", "ROLE_TECHNICIAN");

        verify(toolRegistry, times(2)).resolveDomainTools("ROLE_TECHNICIAN");
    }

    private static CurrentUserContext userContext(String username, UUID userId, String primaryRole) {
        return new CurrentUserContext(
                username,
                userId,
                primaryRole,
                Set.of(primaryRole),
                Set.of(primaryRole, "mcp:chat:execute"),
                Set.of("AUTHENTICATED", "mcp:chat:execute"));
    }

    private SessionAgentManager managerWithToolSelectionEngine(ToolSelectionEngine selectionEngine) {
        return new SessionAgentManager(
                chatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                sharedOrchestrationSupport,
                selectionEngine,
                scopedContentRetrieverFactory,
                null,
                null,
                rolePromptResolver,
                simpleChatFastPath,
                telemetryEmitter,
                null, // openApiToolProvider
                null, // answerResolutionLadder
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

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
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

    @SuppressWarnings("unchecked")
    private static Set<String> roleAgentCacheKeys(SessionAgentManager sessionAgentManager) {
        Cache<String, ?> cache = (Cache<String, ?>) ReflectionTestUtils.getField(sessionAgentManager, "roleAgentCache");
        assertThat(cache).isNotNull();
        cache.cleanUp();
        return cache.asMap().keySet();
    }
}
