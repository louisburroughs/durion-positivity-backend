package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.orchestration.rag.ScopedContentRetrieverFactory;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.service.ToolRegistryService;
import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.RolePromptResolver;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
 * calls) is used rather than a Mockito mock to avoid LangChain4j's
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

    @Mock
    private StreamingChatModel streamingChatModel;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private PgVectorEmbeddingStore embeddingStore;

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

    // Real instance required to prevent @Tool duplicate registration
    private ExaWebSearchTool exaWebSearchTool;
    private InventoryFacadeTool inventoryFacadeTool;
    private OrderFacadeTool orderFacadeTool;
    private SharedOrchestrationSupport sharedOrchestrationSupport;

    private StreamingSessionAgentManager manager;

    @BeforeEach
    void setUp() {
        // Return a fresh mutable list each invocation so buildAgent mutations don't
        // bleed
        when(toolRegistry.resolveDomainTools(any())).thenAnswer(inv -> new ArrayList<>());
        when(toolRegistry.preloadableRoleIdentifiers()).thenReturn(Set.of("ROLE_CASHIER", "ROLE_MANAGER"));
        lenient().when(rolePromptResolver.resolvePrompt(any())).thenReturn("Default role prompt");
        lenient()
                .when(toolSelectionEngine.selectRoleTools(any(), any()))
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
        ContentRetriever scopedRetriever = mock(ContentRetriever.class);
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
                embeddingModel,
                embeddingStore,
                toolRegistry,
                exaWebSearchTool,
                inventoryFacadeTool,
                orderFacadeTool,
                sharedOrchestrationSupport,
                toolSelectionEngine,
                scopedContentRetrieverFactory,
                rolePromptResolver,
                null, // toolAuditService
                30,
                500,
                50,
                100);
        clearInvocations(toolRegistry);
        clearInvocations(toolSelectionEngine);
        clearInvocations(scopedContentRetrieverFactory);
    }

    @Test
    @DisplayName("manager no longer retains tool registry service state")
    void manager_doesNotRetainToolRegistryServiceField() {
        assertThat(java.util.Arrays.stream(StreamingSessionAgentManager.class.getDeclaredFields())
                        .map(field -> field.getName())
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
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "first message");
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "second message");

        verify(toolRegistry, never()).resolveDomainTools("ROLE_CASHIER");
    }

    @Test
    @DisplayName("evict removes cached agent so next streamChat rebuilds it")
    void evict_removesFromCache() {
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "first message");
        manager.evict("user-1");
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "after evict");

        verify(toolRegistry, never()).resolveDomainTools("ROLE_CASHIER");
    }

    @Test
    @DisplayName("streamChat rebuilds agent when role changes for same userId")
    void streamChat_roleChange_rebuildsAgent() {
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "first");
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_MANAGER"), "second");

        verify(toolRegistry, never()).resolveDomainTools("ROLE_CASHIER");
        verify(toolRegistry, never()).resolveDomainTools("ROLE_MANAGER");
    }

    @Test
    @DisplayName("streamChat skips fallback tools already resolved for the role")
    void streamChat_skipsDuplicateFallbackTool() {
        when(toolSelectionEngine.selectRoleTools("ROLE_DUPLICATE", "hello"))
                .thenReturn(new ToolSelectionEngine.ToolSelectionResult(
                        List.of(exaWebSearchTool), List.of(exaWebSearchTool)));

        Flux<String> result =
                manager.streamChat(userContext("user-with-role-tool", USER_ID, "ROLE_DUPLICATE"), "hello");

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
        when(toolRegistry.resolveDomainTools("ROLE_CASHIER", List.of("inventoryFacadeTool")))
                .thenReturn(List.of(inventoryFacadeTool));

        StreamingSessionAgentManager selectorManager = streamingManagerWithToolSelectionEngine(realToolSelectionEngine);
        clearInvocations(toolRegistryService);
        clearInvocations(scopedContentRetrieverFactory);

        Flux<String> result = selectorManager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), message);

        assertThat(result).isNotNull();
        ArgumentCaptor<ToolSelectionContext> contextCaptor = ArgumentCaptor.forClass(ToolSelectionContext.class);
        verify(toolRegistryService).resolveCandidateTools(contextCaptor.capture(), eq(3));
        assertThat(contextCaptor.getValue().workflowState()).isEqualTo("READ");
        assertThat(roleAgentCacheKeys(selectorManager)).contains("ROLE_CASHIER::InventoryFacadeTool");
    }

    @Test
    @DisplayName("streamChat uses shared tool selection even when registry service is unavailable")
    void streamChat_withoutRegistryService_usesSharedSelectionPath() {
        String message = "latest internet sales report";
        when(toolSelectionEngine.selectRoleTools("ROLE_CASHIER", message))
                .thenReturn(new ToolSelectionEngine.ToolSelectionResult(
                        List.of(orderFacadeTool), List.of(exaWebSearchTool, inventoryFacadeTool)));

        Flux<String> result = manager.streamChat(userContext("user-shared-path", USER_ID, "ROLE_CASHIER"), message);

        assertThat(result).isNotNull();
        verify(toolSelectionEngine).selectRoleTools("ROLE_CASHIER", message);
        assertThat(roleAgentCacheKeys(manager))
                .contains("ROLE_CASHIER::ExaWebSearchTool+InventoryFacadeTool+OrderFacadeTool");
    }

    @Test
    @DisplayName("streamChat falls back to shared full role selection when selector returns empty")
    void streamChat_semanticSelection_fallsBackToFullRoleToolsOnEmptyResult() {
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
        assertThat(roleAgentCacheKeys(selectorManager)).contains("ROLE_CASHIER::InventoryFacadeTool+OrderFacadeTool");
    }

    @Test
    @DisplayName("streamChat falls back to shared full role selection when selector throws")
    void streamChat_semanticSelection_fallsBackToFullRoleToolsOnException() {
        ToolSelectionEngine realToolSelectionEngine = realToolSelectionEngine();
        when(toolRegistry.resolveDomainTools("ROLE_CASHIER"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenThrow(new IllegalStateException("selector unavailable"));

        StreamingSessionAgentManager selectorManager = streamingManagerWithToolSelectionEngine(realToolSelectionEngine);
        clearInvocations(toolRegistryService);
        clearInvocations(scopedContentRetrieverFactory);

        Flux<String> result = selectorManager.streamChat(userContext("user-3", USER_ID, "ROLE_CASHIER"), "anything");

        assertThat(result).isNotNull();
        verify(toolRegistryService).resolveCandidateTools(any(ToolSelectionContext.class), eq(3));
        assertThat(roleAgentCacheKeys(selectorManager)).contains("ROLE_CASHIER::InventoryFacadeTool+OrderFacadeTool");
    }

    @Test
    @DisplayName("prebuild merges the full shared fallback tool set")
    void constructor_prebuildRoleAgents_mergesFullSharedFallbackTools() {
        SharedOrchestrationSupport sharedSupportSpy = spy(new SharedOrchestrationSupport());
        when(toolRegistry.resolveDomainTools("ROLE_CASHIER")).thenReturn(new ArrayList<>());
        when(toolRegistry.preloadableRoleIdentifiers()).thenReturn(Set.of("ROLE_CASHIER"));

        new StreamingSessionAgentManager(
                streamingChatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                exaWebSearchTool,
                inventoryFacadeTool,
                orderFacadeTool,
                sharedSupportSpy,
                toolSelectionEngine,
                scopedContentRetrieverFactory,
                rolePromptResolver,
                null,
                30,
                500,
                50,
                100);

        ArgumentCaptor<List<Object>> fallbackToolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(sharedSupportSpy, atLeastOnce())
                .mergeTools(argThat((List<Object> tools) -> tools.isEmpty()), fallbackToolsCaptor.capture());
        assertThat(fallbackToolsCaptor.getAllValues())
                .anySatisfy(fallbackTools -> assertThat(fallbackTools)
                        .containsExactly(exaWebSearchTool, inventoryFacadeTool, orderFacadeTool));
        verify(scopedContentRetrieverFactory, atLeastOnce()).create("master", 10, 0.6);
        verify(scopedContentRetrieverFactory, atLeastOnce()).create("master", 20, 0.55);
    }

    @Test
    @DisplayName("streamChat applies shared inventory fallback selection")
    void streamChat_withInventoryKeyword_includesInventoryFallbackTool() {
        ToolSelectionEngine realToolSelectionEngine = realToolSelectionEngine();
        when(toolRegistry.resolveDomainTools("ROLE_CASHIER")).thenReturn(new ArrayList<>());
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3))).thenReturn(List.of());

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
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3))).thenReturn(List.of());

        StreamingSessionAgentManager selectorManager = streamingManagerWithToolSelectionEngine(realToolSelectionEngine);

        selectorManager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "latest internet news");

        assertThat(roleAgentCacheKeys(selectorManager))
                .contains("ROLE_CASHIER::ExaWebSearchTool")
                .contains("ROLE_CASHIER::full");
    }

    @Test
    @DisplayName("streamChat rebuilds agent after cache TTL expiry")
    void streamChat_rebuildsAgentAfterCacheTtlExpiry() {
        StreamingSessionAgentManager expiringManager = new StreamingSessionAgentManager(
                streamingChatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                exaWebSearchTool,
                inventoryFacadeTool,
                orderFacadeTool,
                sharedOrchestrationSupport,
                toolSelectionEngine,
                scopedContentRetrieverFactory,
                rolePromptResolver,
                null,
                0,
                500,
                50,
                100);
        clearInvocations(toolRegistry);

        expiringManager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "first");
        expiringManager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "second");

        verify(toolSelectionEngine, times(3)).selectRoleTools(eq("ROLE_CASHIER"), any());
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
                embeddingModel,
                embeddingStore,
                toolRegistry,
                exaWebSearchTool,
                inventoryFacadeTool,
                orderFacadeTool,
                sharedOrchestrationSupport,
                selectionEngine,
                scopedContentRetrieverFactory,
                rolePromptResolver,
                null,
                30,
                500,
                50,
                100);
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
        boolean hasInventory = tools.stream().anyMatch(tool -> tool instanceof InventoryFacadeTool);
        boolean hasOrder = tools.stream().anyMatch(tool -> tool instanceof OrderFacadeTool);
        if (hasInventory && !hasOrder) {
            return "inventory";
        }
        if (hasOrder && !hasInventory) {
            return "orders";
        }
        return "master";
    }

    private static CurrentUserContext userContext(String username, UUID userId, String primaryRole) {
        return new CurrentUserContext(
                username, userId, primaryRole, Set.of(primaryRole), Set.of(primaryRole, "mcp:chat:stream"));
    }
}
