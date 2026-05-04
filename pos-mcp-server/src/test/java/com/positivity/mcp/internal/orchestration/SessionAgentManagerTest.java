package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Cache;
import com.positivity.mcp.internal.classification.SimpleChatRuleDefaults;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.service.ToolRegistryService;
import com.positivity.mcp.service.RolePromptResolver;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
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

/**
 * Unit tests for {@link SessionAgentManager}: cache behaviour and eviction.
 *
 * <p>
 * {@code SessionAgentManager} is annotated {@code @Profile("alpha")} so Spring
 * never
 * instantiates it during slice tests. Here we construct it directly via
 * {@code new},
 * bypassing the profile gate, and supply Mockito mocks for all LangChain4j
 * dependencies.
 *
 * <p>
 * {@code AiServices.builder(PosAssistant.class).build()} creates a JDK dynamic
 * proxy.
 * No model calls are made during proxy construction, so a real
 * {@link ExaWebSearchTool}
 * instance (empty API key, never makes HTTP calls) is used rather than a
 * Mockito mock.
 * Using a Mockito mock would trigger a LangChain4j "Duplicated definition for
 * tool: webSearch"
 * error because Mockito subclasses inherit and re-expose the parent's
 * {@code @Tool} annotation.
 */
@ExtendWith(MockitoExtension.class)
class SessionAgentManagerTest {

        @Mock
        private ChatModel chatModel;

        @Mock
        private EmbeddingModel embeddingModel;

        @Mock
        private PgVectorEmbeddingStore embeddingStore;

        @Mock
        private ToolRegistry toolRegistry;

        @Mock
        private ToolRegistryService toolRegistryService;

        @Mock
        private RolePromptResolver rolePromptResolver;

        // Real instance required: Mockito subclasses cause @Tool duplicate registration
        // in LangChain4j
        private ExaWebSearchTool exaWebSearchTool;
        private InventoryFacadeTool inventoryFacadeTool;
        private OrderFacadeTool orderFacadeTool;
        private SimpleChatClassifier simpleChatClassifier;

        private SessionAgentManager manager;

        @BeforeEach
        void setUp() {
                // Return a FRESH list on every invocation so buildAgent mutations don't bleed
                // across calls
                when(toolRegistry.resolveToolsForRole(anyString())).thenAnswer(inv -> new ArrayList<>());
                lenient()
                                .when(toolRegistry.resolveToolsForRole(anyString(), anyCollection()))
                                .thenAnswer(inv -> new ArrayList<>());
                when(toolRegistry.preloadableRoles()).thenReturn(Set.of("ROLE_CASHIER", "ROLE_MANAGER"));
                lenient()
                                .when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class),
                                                anyInt()))
                                .thenReturn(List.of());
                lenient().when(rolePromptResolver.resolvePrompt(anyString())).thenReturn("Default role prompt");
                lenient().when(embeddingModel.embed(anyString()))
                                .thenReturn(Response.from(Embedding.from(new float[] { 0.1f })));
                lenient().when(embeddingStore.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of()));
                exaWebSearchTool = new ExaWebSearchTool(RestClient.builder(), "https://api.exa.ai", "", "auto", 5);
                inventoryFacadeTool = new InventoryFacadeTool(RestClient.builder(), "http://localhost/v1/inventory");
                orderFacadeTool = new OrderFacadeTool(RestClient.builder(), "http://localhost/v1/orders");
                simpleChatClassifier = new SimpleChatClassifier(SimpleChatRuleDefaults.defaultCatalog());
                manager = new SessionAgentManager(
                                chatModel,
                                embeddingModel,
                                embeddingStore,
                                toolRegistry,
                                exaWebSearchTool,
                                inventoryFacadeTool,
                                orderFacadeTool,
                                toolRegistryService,
                                null,
                                rolePromptResolver,
                                simpleChatClassifier,
                                30,
                                500,
                                50,
                                5,
                                100);
                clearInvocations(toolRegistry);
                clearInvocations(toolRegistryService);
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
                when(toolRegistry.resolveToolsForRole("ROLE_DUPLICATE"))
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
                when(chatModel.chat(org.mockito.ArgumentMatchers.<List<ChatMessage>>any()))
                                .thenReturn(ChatResponse.builder()
                                                .aiMessage(AiMessage.from("Hello!"))
                                                .build());

                String response = manager.chat("user-1", "ROLE_ADMIN", "hello");

                assertThat(response).isEqualTo("Hello!");
                verify(toolRegistryService, never()).resolveCandidateTools(any(ToolSelectionContext.class), anyInt());
        }

        @Test
        @DisplayName("chat with business request narrows role tools per message")
        void chat_withBusinessRequest_narrowsRoleToolsPerMessage() {
                ToolMetadata inventoryTool = new ToolMetadata(
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
                when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), anyInt()))
                                .thenReturn(List.of(inventoryTool));
                when(toolRegistry.resolveToolsForRole("ROLE_ADMIN", List.of("inventoryFacadeTool")))
                                .thenReturn(new ArrayList<>(List.of(inventoryFacadeTool)));
                when(chatModel.chat(any(ChatRequest.class)))
                                .thenReturn(ChatResponse.builder()
                                                .aiMessage(AiMessage.from("Stock found"))
                                                .build());

                String response = manager.chat("user-1", "ROLE_ADMIN", "check stock for sku ABC");

                assertThat(response).isEqualTo("Stock found");
                verify(toolRegistryService).resolveCandidateTools(any(ToolSelectionContext.class), anyInt());
                verify(toolRegistry).resolveToolsForRole("ROLE_ADMIN", List.of("inventoryFacadeTool"));
        }

        @Test
        @DisplayName("chat with empty semantic selection falls back to full role tool set")
        void chat_withEmptySemanticSelection_usesFullRoleToolSet() {
                when(toolRegistry.resolveToolsForRole("ROLE_ADMIN"))
                                .thenReturn(new ArrayList<>(List.of(inventoryFacadeTool)));
                when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), anyInt()))
                                .thenReturn(List.of());
                when(chatModel.chat(any(ChatRequest.class)))
                                .thenReturn(ChatResponse.builder()
                                                .aiMessage(AiMessage.from("Stock found"))
                                                .build());

                String response = manager.chat("user-1", "ROLE_ADMIN", "check stock for sku ABC");

                assertThat(response).isEqualTo("Stock found");
                verify(toolRegistry).resolveToolsForRole("ROLE_ADMIN");
                verify(toolRegistry, never()).resolveToolsForRole("ROLE_ADMIN", List.of());
        }

        @Test
        @DisplayName("getOrCreateAgent rebuilds agent after cache TTL expiry")
        void getOrCreateAgent_rebuildsAgentAfterCacheTtlExpiry() {
                SessionAgentManager expiringManager = new SessionAgentManager(
                                chatModel,
                                embeddingModel,
                                embeddingStore,
                                toolRegistry,
                                exaWebSearchTool,
                                inventoryFacadeTool,
                                orderFacadeTool,
                                toolRegistryService,
                                null,
                                rolePromptResolver,
                                simpleChatClassifier,
                                0,
                                500,
                                50,
                                5,
                                100);
                clearInvocations(toolRegistry);

                expiringManager.getOrCreateAgent("user-1", "ROLE_CASHIER");
                expiringManager.getOrCreateAgent("user-1", "ROLE_CASHIER");

                verify(toolRegistry, times(2)).resolveToolsForRole("ROLE_CASHIER");
        }
}
