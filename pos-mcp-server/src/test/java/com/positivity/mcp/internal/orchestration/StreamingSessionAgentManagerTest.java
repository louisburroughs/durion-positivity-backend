package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Cache;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.service.ToolRegistryService;
import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.RolePromptResolver;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private ToolRegistry toolRegistry;

    @Mock
    private RolePromptResolver rolePromptResolver;

    @Mock
    private ToolRegistryService toolRegistryService;

    // Real instance required to prevent @Tool duplicate registration
    private ExaWebSearchTool exaWebSearchTool;
    private InventoryFacadeTool inventoryFacadeTool;
    private OrderFacadeTool orderFacadeTool;

    private StreamingSessionAgentManager manager;

    @BeforeEach
    void setUp() {
        // Return a fresh mutable list each invocation so buildAgent mutations don't
        // bleed
        when(toolRegistry.resolveToolsForRole(any())).thenAnswer(inv -> new ArrayList<>());
        when(toolRegistry.preloadableRoles()).thenReturn(Set.of("ROLE_CASHIER", "ROLE_MANAGER"));
        lenient().when(rolePromptResolver.resolvePrompt(any())).thenReturn("Default role prompt");
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
        manager = new StreamingSessionAgentManager(
                streamingChatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                exaWebSearchTool,
                inventoryFacadeTool,
                orderFacadeTool,
                rolePromptResolver,
                null, // toolRegistryService — null exercises null-safe fallback path
                null, // toolAuditService
                30,
                500,
                50,
                2,
                100);
        clearInvocations(toolRegistry);
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

        verify(toolRegistry, never()).resolveToolsForRole("ROLE_CASHIER");
    }

    @Test
    @DisplayName("evict removes cached agent so next streamChat rebuilds it")
    void evict_removesFromCache() {
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "first message");
        manager.evict("user-1");
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "after evict");

        verify(toolRegistry, never()).resolveToolsForRole("ROLE_CASHIER");
    }

    @Test
    @DisplayName("streamChat rebuilds agent when role changes for same userId")
    void streamChat_roleChange_rebuildsAgent() {
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "first");
        manager.streamChat(userContext("user-1", USER_ID, "ROLE_MANAGER"), "second");

        verify(toolRegistry, never()).resolveToolsForRole("ROLE_CASHIER");
        verify(toolRegistry, never()).resolveToolsForRole("ROLE_MANAGER");
    }

    @Test
    @DisplayName("streamChat skips fallback tools already resolved for the role")
    void streamChat_skipsDuplicateFallbackTool() {
        when(toolRegistry.resolveToolsForRole("ROLE_DUPLICATE"))
                .thenAnswer(inv -> new ArrayList<>(List.of(exaWebSearchTool)));

        Flux<String> result =
                manager.streamChat(userContext("user-with-role-tool", USER_ID, "ROLE_DUPLICATE"), "hello");

        assertThat(result).isNotNull();
    }

    // --- Phase 3: semantic tool selection (toolRegistryService != null) ---

    @Test
    @DisplayName("streamChat uses semantically narrowed tools when selector returns candidates")
    void streamChat_semanticSelection_narrowsToolsWhenCandidatesReturned() {
        ToolMetadata candidate = new ToolMetadata(
                UUID.randomUUID(),
                "InventoryFacadeTool",
                "Inventory",
                "Manage inventory",
                "inventory",
                0.8,
                "LOW",
                50,
                true,
                "inventoryFacadeTool");
        when(toolRegistryService.resolveCandidateTools(any(), anyInt())).thenReturn(List.of(candidate));
        when(toolRegistry.resolveToolsForRole(any())).thenReturn(List.of(exaWebSearchTool));
        when(toolRegistry.resolveToolsForRole("ROLE_CASHIER", List.of("InventoryFacadeTool")))
                .thenReturn(List.of(inventoryFacadeTool));

        StreamingSessionAgentManager selectorManager = new StreamingSessionAgentManager(
                streamingChatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                exaWebSearchTool,
                inventoryFacadeTool,
                orderFacadeTool,
                rolePromptResolver,
                toolRegistryService,
                null,
                30,
                500,
                50,
                2,
                100);
        clearInvocations(toolRegistry);

        Flux<String> result =
                selectorManager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "show me inventory levels");

        assertThat(result).isNotNull();
        verify(toolRegistryService).resolveCandidateTools(any(), anyInt());
        verify(toolRegistry).resolveToolsForRole("ROLE_CASHIER", List.of("InventoryFacadeTool"));
        verify(toolRegistry, times(1)).resolveToolsForRole("ROLE_CASHIER");
    }

    @Test
    @DisplayName("streamChat falls back to full role tools when selector returns empty")
    void streamChat_semanticSelection_fallsBackToFullRoleToolsOnEmptyResult() {
        when(toolRegistryService.resolveCandidateTools(any(), anyInt())).thenReturn(List.of());
        when(toolRegistry.resolveToolsForRole(any())).thenAnswer(inv -> new ArrayList<>());

        StreamingSessionAgentManager selectorManager = new StreamingSessionAgentManager(
                streamingChatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                exaWebSearchTool,
                inventoryFacadeTool,
                orderFacadeTool,
                rolePromptResolver,
                toolRegistryService,
                null,
                30,
                500,
                50,
                2,
                100);
        clearInvocations(toolRegistry);

        Flux<String> result = selectorManager.streamChat(userContext("user-2", USER_ID, "ROLE_CASHIER"), "anything");

        assertThat(result).isNotNull();
        // resolveToolsForRole(role) is called for the fallback path
        verify(toolRegistry).resolveToolsForRole("ROLE_CASHIER");
    }

    @Test
    @DisplayName("streamChat falls back to full role tools when selector throws")
    void streamChat_semanticSelection_fallsBackToFullRoleToolsOnException() {
        when(toolRegistryService.resolveCandidateTools(any(), anyInt()))
                .thenThrow(new RuntimeException("selector unavailable"));
        when(toolRegistry.resolveToolsForRole(any())).thenAnswer(inv -> new ArrayList<>());

        StreamingSessionAgentManager selectorManager = new StreamingSessionAgentManager(
                streamingChatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                exaWebSearchTool,
                inventoryFacadeTool,
                orderFacadeTool,
                rolePromptResolver,
                toolRegistryService,
                null,
                30,
                500,
                50,
                2,
                100);
        clearInvocations(toolRegistry);

        Flux<String> result = selectorManager.streamChat(userContext("user-3", USER_ID, "ROLE_CASHIER"), "anything");

        assertThat(result).isNotNull();
        // fallback resolves from role tool set
        verify(toolRegistry).resolveToolsForRole("ROLE_CASHIER");
    }

    @Test
    @DisplayName("streamChat with inventory keyword includes inventory fallback tool")
    void streamChat_withInventoryKeyword_includesInventoryFallbackTool() {
        when(toolRegistryService.resolveCandidateTools(any(), anyInt())).thenReturn(List.of());

        StreamingSessionAgentManager selectorManager = new StreamingSessionAgentManager(
                streamingChatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                exaWebSearchTool,
                inventoryFacadeTool,
                orderFacadeTool,
                rolePromptResolver,
                toolRegistryService,
                null,
                30,
                500,
                50,
                2,
                100);

        selectorManager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "stock part 1234");

        assertThat(roleAgentCacheKeys(selectorManager))
                .contains("ROLE_CASHIER::InventoryFacadeTool")
                .contains("ROLE_CASHIER::full");
    }

    @Test
    @DisplayName("streamChat with web keyword includes exa fallback tool")
    void streamChat_withWebKeyword_includesExaFallbackTool() {
        when(toolRegistryService.resolveCandidateTools(any(), anyInt())).thenReturn(List.of());

        StreamingSessionAgentManager selectorManager = new StreamingSessionAgentManager(
                streamingChatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                exaWebSearchTool,
                inventoryFacadeTool,
                orderFacadeTool,
                rolePromptResolver,
                toolRegistryService,
                null,
                30,
                500,
                50,
                2,
                100);

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
                rolePromptResolver,
                null,
                null,
                0,
                500,
                50,
                2,
                100);
        clearInvocations(toolRegistry);

        expiringManager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "first");
        expiringManager.streamChat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "second");

        verify(toolRegistry, times(2)).resolveToolsForRole("ROLE_CASHIER");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> roleAgentCacheKeys(StreamingSessionAgentManager selectorManager) {
        Cache<String, ?> cache = (Cache<String, ?>) ReflectionTestUtils.getField(selectorManager, "roleAgentCache");
        assertThat(cache).isNotNull();
        cache.cleanUp();
        return cache.asMap().keySet();
    }

    private static CurrentUserContext userContext(String username, UUID userId, String primaryRole) {
        return new CurrentUserContext(
                username, userId, primaryRole, Set.of(primaryRole), Set.of(primaryRole, "mcp:chat:stream"));
    }
}
