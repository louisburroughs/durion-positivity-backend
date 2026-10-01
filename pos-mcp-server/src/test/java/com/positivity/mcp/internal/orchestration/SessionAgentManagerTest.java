package com.positivity.mcp.internal.orchestration;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
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
import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.ScopeGraphProperties.Consumer;
import com.positivity.mcp.internal.domain.QuestionTags;
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
import com.positivity.mcp.internal.scopegraph.ScopeConsumers;
import com.positivity.mcp.internal.scopegraph.ScopeResolver;
import com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import com.positivity.mcp.internal.service.ConversationMemoryHistory;
import com.positivity.mcp.internal.service.NltiWorkflowStateService;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.RolePromptResolver;
import com.positivity.mcp.internal.service.ToolInvocationRecorder;
import com.positivity.mcp.internal.service.ToolRegistryService;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetry;
import com.positivity.mcp.internal.telemetry.NltiTelemetryEmitter;
import com.positivity.mcp.tenancy.BoundTenant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
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
 * bypassing the profile gate, and supply Mockito mocks for all assistant
 * runtime
 * dependencies.
 *
 * <p>
 * {@code AiServices.builder(PosAssistant.class).build()} creates a JDK dynamic
 * proxy.
 * No model calls are made during proxy construction, so a real
 * {@link ExaWebSearchTool}
 * instance (empty API key, never makes HTTP calls) is used rather than a
 * Mockito mock.
 * Using a Mockito mock would trigger an assistant runtime "Duplicated
 * definition for
 * tool: webSearch"
 * error because Mockito subclasses inherit and re-expose the parent's
 * {@code @Tool} annotation.
 */
@ExtendWith(MockitoExtension.class)
@ExtendWith(BoundTenant.class)
class SessionAgentManagerTest {

    private static final HeuristicQuestionTagger HEURISTIC_TAGGER = HeuristicQuestionTagger.withDefaultCatalog();

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

    @Mock
    private ToolInvocationRecorder toolInvocationRecorder;

    // Real instance required: Mockito subclasses cause @Tool duplicate registration
    // in the assistant runtime
    private DateWindowFacadeTool dateWindowFacadeTool;
    private ExaWebSearchTool exaWebSearchTool;
    private InventoryFacadeTool inventoryFacadeTool;
    private OrderFacadeTool orderFacadeTool;
    private SimpleChatClassifier simpleChatClassifier;
    private SimpleChatFastPath simpleChatFastPath;
    private SharedOrchestrationSupport sharedOrchestrationSupport;

    private SessionAgentManager manager;

    @BeforeEach
    void setUp() {
        // ChatClient (which runs the tool-execution loop) dereferences
        // ChatModel.getOptions()
        // unconditionally, so a bare @Mock returning null options NPEs the whole chat
        // path.
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
                .when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), anyInt()))
                .thenReturn(gated(List.of()));
        // ADR-0068: the mocked engine tags with the heuristics, as the unwired real engine does.
        lenient()
                .when(toolSelectionEngine.tag(anyString()))
                .thenAnswer(inv -> HEURISTIC_TAGGER.tag(inv.getArgument(0)));
        lenient()
                .when(toolSelectionEngine.selectRoleTools(anyString(), anySet(), anyString(), any(QuestionTags.class)))
                .thenReturn(new ToolSelectionEngine.ToolSelectionResult(List.of(), List.of()));
        // #778: default to session-less so existing tests exercise the
        // message-heuristic path.
        lenient().when(workflowStateService.resolveActiveState(anyString())).thenReturn(Optional.empty());
        lenient().when(rolePromptResolver.resolvePrompt(anyString())).thenReturn("Default role prompt");
        lenient()
                .when(rolePromptResolver.assemble(anyString(), anyString(), anyBoolean()))
                .thenReturn(new RolePromptResolver.AssembledPrompt("prompt", List.of("BASE", "ROLE")));
        lenient().when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f});
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
        lenient()
                .when(toolInvocationRecorder.wrap(any(ToolCallback.class), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
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
                toolInvocationRecorder,
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
    @DisplayName("evict clears the actor's memory and rate entries within the bound tenant only")
    void evict_clearsTheActorsEntriesWithinTheTenant() {
        @SuppressWarnings("unchecked")
        Cache<String, org.springframework.ai.chat.memory.ChatMemory> memory =
                (Cache<String, org.springframework.ai.chat.memory.ChatMemory>)
                        ReflectionTestUtils.getField(manager, "chatMemoryCache");
        @SuppressWarnings("unchecked")
        Cache<String, java.util.concurrent.atomic.AtomicInteger> counters =
                (Cache<String, java.util.concurrent.atomic.AtomicInteger>)
                        ReflectionTestUtils.getField(manager, "requestCountCache");
        assertThat(memory).isNotNull();
        assertThat(counters).isNotNull();
        org.springframework.ai.chat.memory.ChatMemory chatMemory =
                mock(org.springframework.ai.chat.memory.ChatMemory.class);
        memory.put(SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", "c1"), chatMemory);
        memory.put(SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_CASHIER", null), chatMemory);
        memory.put(SessionAgentManager.memoryKey(TENANT_A, "user-2", "ROLE_ADMIN", "c1"), chatMemory);
        memory.put(SessionAgentManager.memoryKey(TENANT_B, "user-1", "ROLE_ADMIN", "c1"), chatMemory);
        counters.put(
                SessionAgentManager.actorKey(TENANT_A, "user-1"), new java.util.concurrent.atomic.AtomicInteger(3));
        counters.put(
                SessionAgentManager.actorKey(TENANT_B, "user-1"), new java.util.concurrent.atomic.AtomicInteger(3));

        manager.evict("user-1");

        assertThat(memory.asMap().keySet())
                .containsExactlyInAnyOrder(
                        SessionAgentManager.memoryKey(TENANT_A, "user-2", "ROLE_ADMIN", "c1"),
                        SessionAgentManager.memoryKey(TENANT_B, "user-1", "ROLE_ADMIN", "c1"));
        assertThat(counters.asMap().keySet()).containsExactly(SessionAgentManager.actorKey(TENANT_B, "user-1"));
    }

    @Test
    @DisplayName(
            "persistedConversationId parses only a canonical-UUID 4th segment; ephemeral/no-conversation keys are null (#2073)")
    void persistedConversationId_parsesOnlyCanonicalUuidSuffix() {
        UUID conversationId = UUID.randomUUID();

        assertThat(SessionAgentManager.persistedConversationId(
                        SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", conversationId.toString())))
                .isEqualTo(conversationId);
        assertThat(SessionAgentManager.persistedConversationId(
                        SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", "gate-q07")))
                .as("the deprecated #1735 non-UUID ephemeral key names no persisted conversation")
                .isNull();
        assertThat(SessionAgentManager.persistedConversationId(
                        SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", null)))
                .as("a key with no conversation segment at all")
                .isNull();
    }

    @Test
    @DisplayName("a chat-memory cache miss for a persisted conversation id rehydrates from stored history (#2073)")
    void chatMemory_cacheMissForPersistedConversation_seedsFromHistory() {
        UUID conversationId = UUID.randomUUID();
        String memoryKey = SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", conversationId.toString());
        ConversationMemoryHistory history = mock(ConversationMemoryHistory.class);
        List<Message> stored = List.of(new UserMessage("hi"), new AssistantMessage("hello"));
        when(history.recentTurns(conversationId, 50)).thenReturn(stored);
        manager.setConversationMemoryHistory(history);

        ChatMemory memory = (ChatMemory) ReflectionTestUtils.invokeMethod(manager, "chatMemoryFor", memoryKey);

        assertThat(memory.get(memoryKey)).containsExactlyElementsOf(stored);
        verify(history).recentTurns(conversationId, 50);
    }

    @Test
    @DisplayName(
            "a chat-memory cache miss for the deprecated ephemeral (non-UUID) key never asks the history seam (#2073)")
    void chatMemory_cacheMissForEphemeralKey_neverConsultsHistory() {
        String memoryKey = SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", "gate-q07");
        ConversationMemoryHistory history = mock(ConversationMemoryHistory.class);
        manager.setConversationMemoryHistory(history);

        ChatMemory memory = (ChatMemory) ReflectionTestUtils.invokeMethod(manager, "chatMemoryFor", memoryKey);

        assertThat(memory.get(memoryKey)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(history);
    }

    @Test
    @DisplayName("a chat-memory cache miss for a key with no conversation segment never asks the history seam (#2073)")
    void chatMemory_cacheMissForKeyWithNoConversationId_neverConsultsHistory() {
        String memoryKey = SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", null);
        ConversationMemoryHistory history = mock(ConversationMemoryHistory.class);
        manager.setConversationMemoryHistory(history);

        ChatMemory memory = (ChatMemory) ReflectionTestUtils.invokeMethod(manager, "chatMemoryFor", memoryKey);

        assertThat(memory.get(memoryKey)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(history);
    }

    @Test
    @DisplayName("no ConversationMemoryHistory wired (pre-#2073 profiles) starts memory empty, no NPE")
    void chatMemory_noHistoryWired_startsEmptyWithoutThrowing() {
        UUID conversationId = UUID.randomUUID();
        String memoryKey = SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", conversationId.toString());

        ChatMemory memory = (ChatMemory) ReflectionTestUtils.invokeMethod(manager, "chatMemoryFor", memoryKey);

        assertThat(memory).isNotNull();
        assertThat(memory.get(memoryKey)).isEmpty();
    }

    @Test
    @DisplayName(
            "evictConversation clears cached memory of that conversation across actors/roles within the bound tenant only (#2073)")
    void evictConversation_clearsCachedMemoryWithinTenantOnly() {
        UUID conversationId = UUID.randomUUID();
        @SuppressWarnings("unchecked")
        Cache<String, ChatMemory> memory =
                (Cache<String, ChatMemory>) ReflectionTestUtils.getField(manager, "chatMemoryCache");
        assertThat(memory).isNotNull();
        ChatMemory chatMemory = mock(ChatMemory.class);
        memory.put(
                SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", conversationId.toString()), chatMemory);
        memory.put(
                SessionAgentManager.memoryKey(TENANT_A, "user-2", "ROLE_CASHIER", conversationId.toString()),
                chatMemory);
        memory.put(SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", "other-conversation"), chatMemory);
        memory.put(
                SessionAgentManager.memoryKey(TENANT_B, "user-1", "ROLE_ADMIN", conversationId.toString()), chatMemory);

        manager.evictConversation(conversationId.toString());

        assertThat(memory.asMap().keySet())
                .containsExactlyInAnyOrder(
                        SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", "other-conversation"),
                        SessionAgentManager.memoryKey(TENANT_B, "user-1", "ROLE_ADMIN", conversationId.toString()));
    }

    @Test
    @DisplayName("chat with greeting uses simple no-tool model path")
    void chat_withGreeting_usesSimpleModelPath() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Hello!"));
        CurrentUserContext currentUser = userContext("user-1", USER_ID, "ROLE_ADMIN");

        String response = manager.chat(currentUser, "hello");

        assertThat(response).isEqualTo("Hello!");
        verify(toolInvocationRecorder).beginTurn(currentUser, "hello");
        verify(toolInvocationRecorder).recordSimpleChat(true);
        verify(toolInvocationRecorder).completeTurn("Hello!");
        verify(toolInvocationRecorder).clearTurn();
        // Prompt resolution now happens deferred in systemMessageProvider lambda at
        // runtime
        verify(toolSelectionEngine, never())
                .selectRoleTools(anyString(), anySet(), anyString(), any(QuestionTags.class));
        verify(toolRegistryService, never()).resolveCandidateSelection(any(ToolSelectionContext.class), anyInt());
    }

    @Test
    @DisplayName("chat with business request uses shared workflow derivation and tool narrowing")
    void chat_withBusinessRequest_narrowsRoleToolsPerMessage() {
        String message = "show stock for sku ABC";
        ToolSelectionEngine realToolSelectionEngine = realToolSelectionEngine();
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of(inventoryToolMetadata())));
        when(toolRegistry.resolveToolsByName(List.of("inventoryFacadeTool"))).thenReturn(List.of(inventoryFacadeTool));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Stock found"));
        SessionAgentManager selectorManager = managerWithToolSelectionEngine(realToolSelectionEngine);
        clearInvocations(toolRegistryService);
        clearInvocations(scopedContentRetrieverFactory);

        String response = selectorManager.chat(userContext("user-1", USER_ID, "ROLE_ADMIN"), message);

        assertThat(response).isEqualTo("Stock found");
        // Prompt resolution now happens deferred in systemMessageProvider lambda at
        // runtime
        ArgumentCaptor<ToolSelectionContext> contextCaptor = ArgumentCaptor.forClass(ToolSelectionContext.class);
        verify(toolRegistryService).resolveCandidateSelection(contextCaptor.capture(), eq(3));
        verify(scopedContentRetrieverFactory).create("inventory", 10, 0.6);
        verify(scopedContentRetrieverFactory).create("inventory", 20, 0.55);
        assertThat(contextCaptor.getValue().workflowState()).isEqualTo("IDLE");
        assertThat(roleAgentCacheKeys(selectorManager)).contains("ROLE_ADMIN::GlossaryFacadeTool+InventoryFacadeTool");
    }

    @Test
    @DisplayName("chat emits one nlti.request.telemetry event with actor, tools, prompt layers, SUCCESS")
    void chat_emitsRequestTelemetry() {
        String message = "show stock for sku ABC";
        ToolSelectionEngine realToolSelectionEngine = realToolSelectionEngine();
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of(inventoryToolMetadata())));
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
        // Gate 2C: the resolved workflow state is surfaced (IDLE for this session-less
        // lookup query).
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
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of()));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Stock found"));
        SessionAgentManager selectorManager = managerWithToolSelectionEngine(realToolSelectionEngine);
        clearInvocations(toolRegistryService);
        clearInvocations(scopedContentRetrieverFactory);

        String response = selectorManager.chat(userContext("user-1", USER_ID, "ROLE_ADMIN"), message);

        assertThat(response).isEqualTo("Stock found");
        // Prompt resolution now happens deferred in systemMessageProvider lambda at
        // runtime
        verify(toolRegistryService).resolveCandidateSelection(any(ToolSelectionContext.class), eq(3));
        // Second-order effect of failing closed, asserted rather than glossed: RAG
        // scope is derived
        // from the resolved tool set, so emptying roleTools leaves only the keyword
        // fallback
        // (inventory) and the scope narrows from "master" to "inventory" — less
        // context, which is
        // consistent with a caller the gate authorised for nothing.
        verify(scopedContentRetrieverFactory).create("inventory", 10, 0.6);
        verify(scopedContentRetrieverFactory).create("inventory", 20, 0.55);
        // #1606: an empty gated set no longer substitutes the ungated domain tool set,
        // so the
        // agent is built with no role tools and the cache key reflects that.
        assertThat(roleAgentCacheKeys(selectorManager))
                .doesNotContain("ROLE_ADMIN::GlossaryFacadeTool+InventoryFacadeTool+OrderFacadeTool");
    }

    /**
     * PR #2367 review: production never puts ExaWebSearchTool in the gated set (it has no {@code
     * mcp_tool} row), so the fixture leaves it out and web search must reach the agent through the
     * engine's exemption alone.
     */
    @Test
    @DisplayName("ADR-0068 §2: chat offers web search through the exemption although no gated set names it")
    void chat_withWebKeyword_includesExaFallbackTool() {
        ToolSelectionEngine realToolSelectionEngine = realToolSelectionEngine();
        when(toolRegistry.resolveDomainTools("ROLE_CASHIER")).thenReturn(new ArrayList<>());
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of()));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Here is the news"));
        SessionAgentManager selectorManager = managerWithToolSelectionEngine(realToolSelectionEngine);

        selectorManager.chat(userContext("user-1", USER_ID, "ROLE_CASHIER"), "find latest internet news");

        assertThat(gated(List.of()).gatedToolNames()).doesNotContain("ExaWebSearchTool");
        assertThat(roleAgentCacheKeys(selectorManager)).contains("ROLE_CASHIER::ExaWebSearchTool+GlossaryFacadeTool");
    }

    @Test
    @DisplayName("chat threads the persisted non-IDLE session workflow state into tool selection (#778)")
    void chat_usesPersistedWorkflowState_whenSessionPresent() {
        String message = "create a purchase order for vendor acme";
        when(workflowStateService.resolveActiveState("user-1")).thenReturn(Optional.of(WorkflowState.CREATING_PO));
        when(toolSelectionEngine.selectRoleTools(
                        anyString(), anySet(), anyString(), any(WorkflowState.class), any(QuestionTags.class)))
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
                        eq(WorkflowState.CREATING_PO),
                        any(QuestionTags.class));
        verify(toolSelectionEngine, never())
                .selectRoleTools(anyString(), anySet(), anyString(), any(QuestionTags.class));
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
                toolInvocationRecorder,
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

        expiringManager.getOrCreateAgent("user-1", "ROLE_CASHIER");
        expiringManager.getOrCreateAgent("user-1", "ROLE_CASHIER");

        verify(toolRegistry, times(2)).resolveDomainTools("ROLE_CASHIER");
    }

    @Test
    @DisplayName("onAgentConfigurationChanged evicts prebuilt role agents so the next request rebuilds")
    void onAgentConfigurationChanged_evictsCachedRoleAgents() {
        // Prebuilt at construction time — a warm request is served from cache without a
        // rebuild.
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

    // ─── #2075: chatTurn's profile-independent TurnSummary ─────────────────────────────────────

    @Test
    @DisplayName("chatTurn on the simple-chat path reports SIMPLE_CHAT with the extracted source and no tools (#2075)")
    void chatTurn_simpleChatPath_reportsSimpleChatSourceNoTools() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Hello!"));
        CurrentUserContext currentUser = userContext("user-1", USER_ID, "ROLE_ADMIN");

        long beforeMillis = System.currentTimeMillis();
        com.positivity.mcp.internal.domain.ChatOutcome outcome = manager.chatTurn(currentUser, "hello", null, null);
        long observedElapsedMs = System.currentTimeMillis() - beforeMillis;

        assertThat(outcome.text()).isEqualTo("Hello!");
        assertThat(outcome.summary().answerPath())
                .isEqualTo(com.positivity.mcp.internal.domain.TurnSummary.PATH_SIMPLE_CHAT);
        assertThat(outcome.summary().answerSource()).isEqualTo("CONTENT");
        assertThat(outcome.summary().toolsCalled()).isEmpty();
        // #2075 (Wave 4 fix): latencyMs is now measured inside simpleChat at the same point as the
        // path's own elapsedMs (before the audit log/telemetry), so it must fall within this test's
        // own wall-clock bracket around the whole call — never negative, never past "now".
        assertThat(outcome.summary().latencyMs())
                .isGreaterThanOrEqualTo(0)
                .isLessThanOrEqualTo((int) observedElapsedMs + 50);
    }

    @Test
    @DisplayName("chatTurn on the agent path reports AGENT with the reply's source and tools (#2075)")
    void chatTurn_agentPath_reportsAgentSourceAndTools() {
        PosAssistant stubAgent = mock(PosAssistant.class);
        when(stubAgent.reply(anyString(), eq("show stock for sku ABC"), anyString()))
                .thenReturn(new PosAssistant.Reply("Stock found", "CONTENT", List.of("InventoryFacadeTool")));
        seedRoleAgentCache("ROLE_ADMIN", stubAgent);

        com.positivity.mcp.internal.domain.ChatOutcome outcome =
                manager.chatTurn(userContext("user-1", USER_ID, "ROLE_ADMIN"), "show stock for sku ABC", null, null);

        assertThat(outcome.text()).isEqualTo("Stock found");
        assertThat(outcome.summary().answerPath()).isEqualTo(com.positivity.mcp.internal.domain.TurnSummary.PATH_AGENT);
        assertThat(outcome.summary().answerSource()).isEqualTo("CONTENT");
        assertThat(outcome.summary().toolsCalled()).containsExactly("InventoryFacadeTool");
        assertThat(outcome.summary().latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("chatTurn stamps the recorder with the parsed conversation id right after beginTurn (#2075)")
    void chatTurn_recordsMessageAfterBeginTurn() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Hello!"));
        UUID conversationId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        CurrentUserContext currentUser = userContext("user-1", USER_ID, "ROLE_ADMIN");

        manager.chatTurn(currentUser, "hello", conversationId.toString(), assistantMessageId);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(toolInvocationRecorder);
        inOrder.verify(toolInvocationRecorder).beginTurn(currentUser, "hello");
        inOrder.verify(toolInvocationRecorder).recordMessage(conversationId, assistantMessageId);
    }

    @Test
    @DisplayName("chatTurn: a non-UUID (ephemeral #1735) conversationId records a null conversation id (#2075)")
    void chatTurn_nonUuidConversationId_recordsNullConversationId() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Hello!"));
        CurrentUserContext currentUser = userContext("user-1", USER_ID, "ROLE_ADMIN");

        manager.chatTurn(currentUser, "hello", "gate-q07", null);

        verify(toolInvocationRecorder).recordMessage(null, null);
    }

    @Test
    @DisplayName("the 3-argument chat(user, message, conversationId) still returns just the text (#2075)")
    void chat_threeArg_stillReturnsText() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Hello!"));

        String response = manager.chat(userContext("user-1", USER_ID, "ROLE_ADMIN"), "hello", null);

        assertThat(response).isEqualTo("Hello!");
    }

    /**
     * Seeds {@code roleAgentCache} with a stub {@link PosAssistant} at exactly the key
     * {@code chatTurn}'s agent path would compute for a message the default (unstubbed)
     * {@code toolSelectionEngine} routes to empty role/fallback tool lists and no tier — so the
     * agent path uses the stub instead of building a real {@code SpringAiPosAssistant}, letting
     * the test control {@link PosAssistant.Reply} directly.
     */
    private void seedRoleAgentCache(String role, PosAssistant agent) {
        List<Object> selectedTools = sharedOrchestrationSupport.mergeTools(List.of(), List.of());
        String toolCacheKey = sharedOrchestrationSupport.toolCacheKey(selectedTools);
        String key = (String) ReflectionTestUtils.invokeMethod(manager, "agentCacheKey", role, toolCacheKey, null);
        @SuppressWarnings("unchecked")
        Cache<String, PosAssistant> cache =
                (Cache<String, PosAssistant>) ReflectionTestUtils.getField(manager, "roleAgentCache");
        assertThat(cache).isNotNull();
        cache.put(key, agent);
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

    // ── ADR-0069 §5 / §9: the scope is published, recorded and cleared, and changes nothing ──

    private static final String SCOPE_MESSAGE = "is the work order WO-20391 in stock at the store?";

    private static ScopeSet scopeOf(String message) {
        return ScopeResolverFixtures.resolver(ScopeResolverFixtures.shadow(60), new SimpleMeterRegistry())
                .resolve(message, Set.of("AUTHENTICATED", ScopeResolverFixtures.WORKORDER_VIEW), WorkflowState.IDLE);
    }

    private SessionAgentManager scopeManager(
            ToolSelectionEngine selectionEngine,
            SharedOrchestrationSupport support,
            RequestScopedUserContext requestContext,
            ToolInvocationRecorder recorder,
            ScopeConsumers scopeConsumers) {
        return new SessionAgentManager(
                chatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                support,
                selectionEngine,
                scopedContentRetrieverFactory,
                null,
                null,
                rolePromptResolver,
                simpleChatFastPath,
                telemetryEmitter,
                null, // openApiToolProvider
                null, // answerResolutionLadder
                requestContext,
                null, // observationRegistry (#1655)
                null, // roleDefaultPermissionsClient
                recorder,
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
                scopeConsumers);
    }

    /** What the agent saw in the request-scoped holder while it ran. */
    private record SeenByAgent(Optional<ScopeSet> scope, Optional<CurrentUserContext> caller, QuestionTags tags) {}

    /** Seeds {@code target}'s cache with an agent that notes what is published while it runs. */
    private java.util.concurrent.atomic.AtomicReference<SeenByAgent> seedObservingAgent(
            SessionAgentManager target,
            RequestScopedUserContext requestContext,
            String role,
            RuntimeException failure) {
        java.util.concurrent.atomic.AtomicReference<SeenByAgent> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        PosAssistant agent = mock(PosAssistant.class);
        when(agent.reply(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            seen.set(new SeenByAgent(
                    requestContext.currentScope(), requestContext.current(), requestContext.currentTags()));
            if (failure != null) {
                throw failure;
            }
            return new PosAssistant.Reply("answer", "CONTENT", List.of());
        });
        List<Object> selectedTools = sharedOrchestrationSupport.mergeTools(List.of(), List.of());
        String key = (String) ReflectionTestUtils.invokeMethod(
                target, "agentCacheKey", role, sharedOrchestrationSupport.toolCacheKey(selectedTools), null);
        @SuppressWarnings("unchecked")
        Cache<String, PosAssistant> cache =
                (Cache<String, PosAssistant>) ReflectionTestUtils.getField(target, "roleAgentCache");
        assertThat(cache).isNotNull();
        cache.put(key, agent);
        return seen;
    }

    @Test
    @DisplayName(
            "ADR-0068: the turn is tagged once, ahead of the simple-chat decision, recorded, published next to the caller and cleared with it")
    void chat_tagsOnce_recordsPublishesAndClearsTheTags() {
        QuestionTags tags = HEURISTIC_TAGGER.tag(SCOPE_MESSAGE);
        when(toolSelectionEngine.tag(SCOPE_MESSAGE)).thenReturn(tags);
        when(toolSelectionEngine.selectRoleTools(anyString(), anySet(), anyString(), same(tags)))
                .thenReturn(new ToolSelectionEngine.ToolSelectionResult(List.of(), List.of()));
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        SessionAgentManager scoped = scopeManager(
                toolSelectionEngine, sharedOrchestrationSupport, requestContext, toolInvocationRecorder, null);
        java.util.concurrent.atomic.AtomicReference<SeenByAgent> seen =
                seedObservingAgent(scoped, requestContext, "ROLE_ADMIN", null);
        CurrentUserContext caller = userContext("user-1", USER_ID, "ROLE_ADMIN");

        scoped.chat(caller, SCOPE_MESSAGE);

        // One tagging call for the turn (the warm-up in the constructor never tags).
        verify(toolSelectionEngine, times(1)).tag(anyString());
        // Published for the agent's window, next to the caller.
        assertThat(seen.get().tags()).isSameAs(tags);
        assertThat(seen.get().caller()).contains(caller);
        // Cleared with the caller, in the same finally.
        assertThat(requestContext.currentTags().isNone()).isTrue();
        assertThat(requestContext.current()).isEmpty();
        // Recorded first: the simple-chat decision and every later stage read the record.
        org.mockito.InOrder stages = org.mockito.Mockito.inOrder(toolSelectionEngine, toolInvocationRecorder);
        stages.verify(toolSelectionEngine).tag(SCOPE_MESSAGE);
        stages.verify(toolInvocationRecorder).recordTags(tags);
        stages.verify(toolInvocationRecorder).recordSimpleChat(false);
        stages.verify(toolSelectionEngine).selectRoleTools(anyString(), anySet(), anyString(), same(tags));
        stages.verify(toolInvocationRecorder).completeTurn("answer");

        ArgumentCaptor<NltiRequestTelemetry> event = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter).emit(event.capture());
        assertThat(event.getValue().schemaVersion()).isEqualTo(3);
        assertThat(event.getValue().tagging()).isNotNull();
        assertThat(event.getValue().tagging().mode()).isEqualTo("OFF");
        assertThat(event.getValue().tagging().simpleChat()).isFalse();
        assertThat(event.getValue().tagging().workflowState())
                .isEqualTo(tags.workflowState().name());
        // The acting tag values live in the tagging block only; routing classification is the
        // Gate 4 router's, and the router did not run (tiering is off).
        assertThat(event.getValue().tagging().intent()).isEqualTo("UNKNOWN");
        assertThat(event.getValue().routing().intentType()).isNull();
        assertThat(event.getValue().routing().riskLevel()).isNull();
        assertThat(event.getValue().routing().domain()).isNull();
        assertThat(event.getValue().routing().complexity()).isNull();
    }

    @Test
    @DisplayName("ADR-0068: warm-up selects with QuestionTags.none() and never tags")
    void prebuild_neverTags() {
        // Constructing a manager warms ROLE_CASHIER and ROLE_MANAGER.
        scopeManager(
                toolSelectionEngine,
                sharedOrchestrationSupport,
                new RequestScopedUserContext(),
                toolInvocationRecorder,
                null);

        verify(toolSelectionEngine, never()).tag(anyString());
        verify(toolSelectionEngine, atLeastOnce())
                .selectRoleTools(eq("ROLE_CASHIER"), anySet(), eq("ROLE_CASHIER"), same(QuestionTags.none()));
    }

    @Test
    @DisplayName(
            "ADR-0069: a resolved scope is recorded with the selection stages, published next to the caller, and cleared with it")
    void chat_shadow_recordsPublishesAndClearsTheScope() {
        ScopeSet scope = scopeOf(SCOPE_MESSAGE);
        when(toolSelectionEngine.selectRoleTools(anyString(), anySet(), anyString(), any(QuestionTags.class)))
                .thenReturn(
                        new ToolSelectionEngine.ToolSelectionResult(List.of(), List.of(), WorkflowState.IDLE, scope));
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        SessionAgentManager scoped = scopeManager(
                toolSelectionEngine,
                sharedOrchestrationSupport,
                requestContext,
                toolInvocationRecorder,
                ScopeResolverFixtures.consumers(ScopeResolverFixtures.shadow(60), new SimpleMeterRegistry()));
        java.util.concurrent.atomic.AtomicReference<SeenByAgent> seen =
                seedObservingAgent(scoped, requestContext, "ROLE_ADMIN", null);
        CurrentUserContext caller = userContext("user-1", USER_ID, "ROLE_ADMIN");

        scoped.chat(caller, SCOPE_MESSAGE);

        // Published for the agent's window, next to the caller.
        assertThat(seen.get().scope()).containsSame(scope);
        assertThat(seen.get().caller()).contains(caller);
        // Cleared with the caller, in the same finally.
        assertThat(requestContext.currentScope()).isEmpty();
        assertThat(requestContext.current()).isEmpty();
        // Recorded where the other selection stages are, before the agent runs.
        org.mockito.InOrder stages = org.mockito.Mockito.inOrder(toolInvocationRecorder);
        stages.verify(toolInvocationRecorder).recordSelectedTools(any());
        stages.verify(toolInvocationRecorder).recordScope(scope);
        stages.verify(toolInvocationRecorder).completeTurn("answer");
        stages.verify(toolInvocationRecorder).clearTurn();

        ArgumentCaptor<NltiRequestTelemetry> event = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter).emit(event.capture());
        assertThat(event.getValue().schemaVersion()).isEqualTo(3);
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
    @DisplayName("ADR-0069: in mode off (no scope on the selection) nothing is recorded, published or reported")
    void chat_off_recordsAndPublishesNoScope() {
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        SessionAgentManager off = scopeManager(
                toolSelectionEngine,
                sharedOrchestrationSupport,
                requestContext,
                toolInvocationRecorder,
                ScopeResolverFixtures.consumers(ScopeGraphProperties.off(), new SimpleMeterRegistry()));
        java.util.concurrent.atomic.AtomicReference<SeenByAgent> seen =
                seedObservingAgent(off, requestContext, "ROLE_ADMIN", null);

        off.chat(userContext("user-1", USER_ID, "ROLE_ADMIN"), SCOPE_MESSAGE);

        assertThat(seen.get().caller()).isPresent();
        assertThat(seen.get().scope()).isEmpty();
        verify(toolInvocationRecorder, never()).recordScope(any());
        ArgumentCaptor<NltiRequestTelemetry> event = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter).emit(event.capture());
        assertThat(event.getValue().scopeMode()).isNull();
        assertThat(event.getValue().scopeGraphHash()).isNull();
        assertThat(event.getValue().scopeConfidence()).isNull();
        assertThat(event.getValue().scopeEntityCount()).isNull();
    }

    @Test
    @DisplayName("ADR-0069: a turn that fails still clears the scope, and its ERROR telemetry carries none")
    void chat_failure_clearsTheScope() {
        ScopeSet scope = scopeOf(SCOPE_MESSAGE);
        when(toolSelectionEngine.selectRoleTools(anyString(), anySet(), anyString(), any(QuestionTags.class)))
                .thenReturn(
                        new ToolSelectionEngine.ToolSelectionResult(List.of(), List.of(), WorkflowState.IDLE, scope));
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        SessionAgentManager scoped = scopeManager(
                toolSelectionEngine, sharedOrchestrationSupport, requestContext, toolInvocationRecorder, null);
        java.util.concurrent.atomic.AtomicReference<SeenByAgent> seen = seedObservingAgent(
                scoped, requestContext, "ROLE_ADMIN", new IllegalStateException("model unavailable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> scoped.chat(userContext("user-1", USER_ID, "ROLE_ADMIN"), SCOPE_MESSAGE))
                .isInstanceOf(IllegalStateException.class);

        assertThat(seen.get().scope()).containsSame(scope);
        assertThat(requestContext.currentScope()).isEmpty();
        assertThat(requestContext.current()).isEmpty();
        verify(toolInvocationRecorder).recordScope(scope);
        verify(toolInvocationRecorder).failTurn(any());
        ArgumentCaptor<NltiRequestTelemetry> event = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter).emit(event.capture());
        assertThat(event.getValue().outcome().status()).isEqualTo("ERROR");
        assertThat(event.getValue().scopeMode()).isNull();
    }

    @Test
    @DisplayName("ADR-0069: the simple-chat fast path resolves, records and publishes no scope")
    void chat_simpleChat_resolvesNoScope() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Hello!"));
        RequestScopedUserContext requestContext = org.mockito.Mockito.spy(new RequestScopedUserContext());
        SessionAgentManager scoped = scopeManager(
                toolSelectionEngine,
                sharedOrchestrationSupport,
                requestContext,
                toolInvocationRecorder,
                ScopeResolverFixtures.consumers(ScopeResolverFixtures.shadow(60), new SimpleMeterRegistry()));
        clearInvocations(toolSelectionEngine);

        scoped.chat(userContext("user-1", USER_ID, "ROLE_ADMIN"), "hello");

        verify(toolSelectionEngine, never())
                .selectRoleTools(anyString(), anySet(), anyString(), any(QuestionTags.class));
        verify(toolInvocationRecorder, never()).recordScope(any());
        verify(requestContext, never()).recordScope(any());
        ArgumentCaptor<NltiRequestTelemetry> event = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter).emit(event.capture());
        assertThat(event.getValue().scopeMode()).isNull();
    }

    /** One full agent turn with a real selection engine; returns everything a scope could have changed. */
    private List<Object> observableTurn(ScopeResolver resolver) {
        return observableTurn(resolver, null);
    }

    /** As above, with a consumer switch wired into the engine and the manager. */
    private List<Object> observableTurn(ScopeResolver resolver, ScopeConsumers consumers) {
        clearInvocations(chatModel, scopedContentRetrieverFactory, rolePromptResolver, toolRegistryService);
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
        SessionAgentManager target = scopeManager(engine, fixedClockSupport, requestContext, null, consumers);
        clearInvocations(scopedContentRetrieverFactory, rolePromptResolver, toolRegistryService);

        String response = target.chat(userContext("user-1", USER_ID, "ROLE_ADMIN"), SCOPE_MESSAGE);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, org.mockito.Mockito.atLeastOnce()).call(prompts.capture());
        ArgumentCaptor<ToolSelectionContext> ranking = ArgumentCaptor.forClass(ToolSelectionContext.class);
        verify(toolRegistryService).resolveCandidateSelection(ranking.capture(), eq(3));
        List<Object> observed = new ArrayList<>();
        observed.add(response);
        observed.add(roleAgentCacheKeys(target).stream().sorted().toList());
        observed.add(ranking.getValue());
        // The assembled prompt: every message the model received, and the tools it was offered.
        observed.add(prompts.getAllValues().stream().map(Prompt::getContents).toList());
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
            "ADR-0069: in shadow, tool selection, the retrievers and the assembled prompt are exactly what they are in off")
    void chat_shadow_isIdenticalToOff() {
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenAnswer(invocation -> new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of(inventoryToolMetadata())));
        when(toolRegistry.resolveToolsByName(List.of("inventoryFacadeTool")))
                .thenAnswer(invocation -> new ArrayList<>(List.of(inventoryFacadeTool)));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Stock found"));
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

    // ── ADR-0069 §6 / §7: the consumers, each behind its own switch ──

    private static final String WORKORDER_ONLY_MESSAGE = "what is the status of work order WO-20391?";

    /** "ticket" denotes two entities (LOW); long enough to stay off the simple-chat fast path; names no other term. */
    private static final String LOW_MESSAGE =
            "can you please open the ticket for me and tell me everything that is going on with it right now";

    private static Document chunk(String documentId, String ragScope, String text) {
        return new Document(text, java.util.Map.of("document_id", documentId, "rag_scope", ragScope));
    }

    /** A real engine over the fixture graph, wired to {@code consumers}, ranking to the order facade alone. */
    private ToolSelectionEngine orderRankingEngine(ScopeConsumers consumers, SimpleMeterRegistry meters) {
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of(orderToolMetadata())));
        when(toolRegistry.resolveToolsByName(List.of("orderFacadeTool")))
                .thenAnswer(invocation -> new ArrayList<>(List.of(orderFacadeTool)));
        ToolSelectionEngine engine = realToolSelectionEngine();
        engine.setScopeResolver(ScopeResolverFixtures.resolver(consumers.properties(), meters));
        engine.setScopeConsumers(consumers);
        return engine;
    }

    private static ToolMetadata orderToolMetadata() {
        return new ToolMetadata(
                UUID.randomUUID(),
                "orderFacadeTool",
                "Orders",
                "Order lookup",
                "orders",
                1.0,
                "low",
                200,
                true,
                "orderFacadeTool");
    }

    private static List<String> systemPrompts(ChatModel model) {
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, org.mockito.Mockito.atLeastOnce()).call(prompts.capture());
        return prompts.getAllValues().stream()
                .map(prompt -> prompt.getSystemMessage().getText())
                .toList();
    }

    @Test
    @DisplayName("ADR-0069 §9: mode enforce with an empty consumer list is exactly shadow (and shadow is exactly off)")
    void chat_enforceWithoutConsumers_isIdenticalToShadow() {
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenAnswer(invocation -> new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of(inventoryToolMetadata())));
        when(toolRegistry.resolveToolsByName(List.of("inventoryFacadeTool")))
                .thenAnswer(invocation -> new ArrayList<>(List.of(inventoryFacadeTool)));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Stock found"));
        ScopeGraphProperties enforceNothing = ScopeResolverFixtures.enforce(60);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        List<Object> off = observableTurn(null);
        List<Object> shadow = observableTurn(ScopeResolverFixtures.resolver(ScopeResolverFixtures.shadow(60), meters));
        List<Object> enforce = observableTurn(
                ScopeResolverFixtures.resolver(enforceNothing, meters),
                ScopeResolverFixtures.consumers(enforceNothing, meters));

        assertThat(enforce).isEqualTo(shadow);
        assertThat(shadow).isEqualTo(off);
        assertThat(meters.get("mcp.scope.resolved")
                        .tag("confidence", "HIGH")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(meters.find("mcp.scope.fallback").counters().stream()
                        .mapToDouble(counter -> counter.count())
                        .sum())
                .isZero();
    }

    @Test
    @DisplayName(
            "ADR-0069 §6 rag: the retrievers cover all scopes and the hook narrows to the scope plus master before the top-K cut")
    void chat_ragEnforced_buildsAllScopeRetrieversAndNarrowsBeforeTheTopKCut() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScopeConsumers consumers =
                ScopeResolverFixtures.consumers(ScopeResolverFixtures.enforce(60, Consumer.RAG), meters);
        // Five out-of-scope chunks ahead of the two eligible ones: without the hook before the cut,
        // the top-5 would be all inventory and the workorder document would never reach the prompt.
        List<Document> pool = List.of(
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
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("In progress"));
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        SessionAgentManager target = scopeManager(
                orderRankingEngine(consumers, meters),
                sharedOrchestrationSupport,
                requestContext,
                toolInvocationRecorder,
                consumers);
        clearInvocations(scopedContentRetrieverFactory, telemetryEmitter);

        target.chat(
                new CurrentUserContext(
                        "user-1",
                        USER_ID,
                        "ROLE_ADMIN",
                        Set.of("ROLE_ADMIN"),
                        Set.of("ROLE_ADMIN"),
                        Set.of("AUTHENTICATED", ScopeResolverFixtures.WORKORDER_VIEW)),
                WORKORDER_ONLY_MESSAGE);

        // The agent's own scope is "orders" (the order facade alone), but every retriever was built
        // over all scopes, the factory's unfiltered case.
        verify(scopedContentRetrieverFactory, org.mockito.Mockito.times(2)).create(eq("master"), anyInt(), anyDouble());
        verify(scopedContentRetrieverFactory).createLexical("master");
        verify(scopedContentRetrieverFactory, never()).create(eq("orders"), anyInt(), anyDouble());
        String systemPrompt = systemPrompts(chatModel).getLast();
        assertThat(systemPrompt).contains("Workorder lifecycle states", "Glossary of terms");
        assertThat(systemPrompt).doesNotContain("Inventory", "Orders FAQ");
        verify(toolInvocationRecorder).recordScopeConsumers(List.of(), true);
        ArgumentCaptor<NltiRequestTelemetry> event = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter).emit(event.capture());
        assertThat(event.getValue().scopeMode()).isEqualTo("ENFORCE");
        assertThat(event.getValue().scopeRagFilterApplied()).isTrue();
        assertThat(event.getValue().scopeAddedToolCount()).isZero();
        assertThat(event.getValue().rag().promptLayers()).doesNotContain(NltiRequestTelemetry.PromptLayer.SCOPE_CARD);
        assertThat(meters.get("mcp.scope.fallback")
                        .tag("consumer", "rag")
                        .counter()
                        .count())
                .isZero();
    }

    @Test
    @DisplayName("ADR-0069 §6 rag, LOW turn: the hook re-applies the agent's own eligibility and counts a fallback")
    void chat_ragEnforced_lowConfidence_fallsBackToTodaysEligibility() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScopeConsumers consumers =
                ScopeResolverFixtures.consumers(ScopeResolverFixtures.enforce(60, Consumer.RAG), meters);
        List<Document> pool = List.of(
                chunk("workorder.status-lifecycle", "workorder", "Workorder lifecycle states"),
                chunk("glossary", "master", "Glossary of terms"),
                chunk("orders.faq", "orders", "Orders FAQ"));
        when(scopedContentRetrieverFactory.create(anyString(), anyInt(), anyDouble()))
                .thenReturn(query -> pool);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Sure"));
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        SessionAgentManager target = scopeManager(
                orderRankingEngine(consumers, meters), sharedOrchestrationSupport, requestContext, null, consumers);
        clearInvocations(telemetryEmitter);

        // "ticket" is ambiguous: LOW. The ranked order facade makes the agent's own scope "orders".
        target.chat(userContext("user-1", USER_ID, "ROLE_ADMIN"), LOW_MESSAGE);

        String systemPrompt = systemPrompts(chatModel).getLast();
        assertThat(systemPrompt).contains("Orders FAQ", "Glossary of terms").doesNotContain("Workorder lifecycle");
        ArgumentCaptor<NltiRequestTelemetry> event = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter).emit(event.capture());
        assertThat(event.getValue().scopeConfidence()).isEqualTo("LOW");
        assertThat(event.getValue().scopeRagFilterApplied()).isFalse();
        assertThat(meters.get("mcp.scope.fallback")
                        .tag("consumer", "rag")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName(
            "ADR-0069 §7 card: appended per request as the final SCOPE_CARD layer on HIGH, absent on LOW, never baked into the cached agent")
    void chat_cardEnforced_appendsTheCardPerRequest() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScopeConsumers consumers =
                ScopeResolverFixtures.consumers(ScopeResolverFixtures.enforce(60, Consumer.CARD), meters);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Done"));
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        SessionAgentManager target = scopeManager(
                orderRankingEngine(consumers, meters),
                sharedOrchestrationSupport,
                requestContext,
                toolInvocationRecorder,
                consumers);
        clearInvocations(telemetryEmitter, scopedContentRetrieverFactory);
        CurrentUserContext caller = userContext("user-1", USER_ID, "ROLE_ADMIN");

        target.chat(caller, WORKORDER_ONLY_MESSAGE);

        String withCard = systemPrompts(chatModel).getLast();
        int cardStart =
                withCard.indexOf("SCOPE (platform definitions for this question; orientation only, grants nothing)");
        assertThat(cardStart).isPositive();
        String card = withCard.substring(cardStart);
        assertThat(card)
                .contains("Entities: workorder (domain workorder)")
                .doesNotContain("WO-20391", "status of work order");
        // Card first, then the caller-context suffix and no RAG context (the mocked retriever returns nothing).
        assertThat(withCard.substring(0, cardStart)).isEqualTo("prompt\n\n");
        ArgumentCaptor<NltiRequestTelemetry> first = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter).emit(first.capture());
        assertThat(first.getValue().rag().promptLayers())
                .containsExactly(
                        NltiRequestTelemetry.PromptLayer.BASE,
                        NltiRequestTelemetry.PromptLayer.ROLE,
                        NltiRequestTelemetry.PromptLayer.SCOPE_CARD);
        assertThat(first.getValue().scopeRagFilterApplied()).isFalse();
        // The card consumer leaves retrieval construction alone: today's scoped retrievers.
        verify(scopedContentRetrieverFactory, never()).create(eq("master"), anyInt(), anyDouble());
        assertThat(requestContext.currentScopeCard()).isEmpty();
        clearInvocations(chatModel, telemetryEmitter);

        // Same role, same tools, same cached agent: a LOW turn ("ticket" is ambiguous) gets no card.
        target.chat(caller, LOW_MESSAGE);

        assertThat(systemPrompts(chatModel).getLast()).doesNotContain("SCOPE (");
        ArgumentCaptor<NltiRequestTelemetry> second = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter).emit(second.capture());
        assertThat(second.getValue().rag().promptLayers())
                .containsExactly(NltiRequestTelemetry.PromptLayer.BASE, NltiRequestTelemetry.PromptLayer.ROLE);
        assertThat(second.getValue().scopeConfidence()).isEqualTo("LOW");
        assertThat(meters.get("mcp.scope.fallback")
                        .tag("consumer", "card")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(roleAgentCacheKeys(target).stream()
                        .filter(key -> key.startsWith("ROLE_ADMIN::"))
                        .count())
                .as("both turns selected the same tools and shared one cached agent")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-0069 §6 tools: the facades the scope added are selected, cached by and reported for the turn")
    void chat_toolsEnforced_reportsTheAddedFacades() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScopeConsumers consumers =
                ScopeResolverFixtures.consumers(ScopeResolverFixtures.enforce(60, Consumer.TOOLS), meters);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Done"));
        RequestScopedUserContext requestContext = new RequestScopedUserContext();
        // The engine is mocked here: the slot arithmetic has its own tests; this is the manager's side.
        ScopeSet scope = ScopeResolverFixtures.resolver(consumers.properties(), meters)
                .resolve(
                        WORKORDER_ONLY_MESSAGE,
                        Set.of("AUTHENTICATED", ScopeResolverFixtures.WORKORDER_VIEW),
                        WorkflowState.IDLE);
        when(toolSelectionEngine.selectRoleTools(anyString(), anySet(), anyString(), any(QuestionTags.class)))
                .thenReturn(new ToolSelectionEngine.ToolSelectionResult(
                        List.of(orderFacadeTool, inventoryFacadeTool),
                        List.of(),
                        WorkflowState.IDLE,
                        scope,
                        List.of("InventoryFacadeTool")));
        SessionAgentManager target = scopeManager(
                toolSelectionEngine, sharedOrchestrationSupport, requestContext, toolInvocationRecorder, consumers);
        clearInvocations(telemetryEmitter);

        target.chat(userContext("user-1", USER_ID, "ROLE_ADMIN"), WORKORDER_ONLY_MESSAGE);

        verify(toolInvocationRecorder).recordSelectedTools(List.of("OrderFacadeTool", "InventoryFacadeTool"));
        verify(toolInvocationRecorder).recordScopeConsumers(List.of("InventoryFacadeTool"), false);
        ArgumentCaptor<NltiRequestTelemetry> event = ArgumentCaptor.forClass(NltiRequestTelemetry.class);
        verify(telemetryEmitter).emit(event.capture());
        assertThat(event.getValue().scopeAddedToolCount()).isEqualTo(1);
        assertThat(event.getValue().tools().selected()).containsExactly("OrderFacadeTool", "InventoryFacadeTool");
        assertThat(roleAgentCacheKeys(target)).contains("ROLE_ADMIN::InventoryFacadeTool+OrderFacadeTool");
        assertThat(meters.get("mcp.scope.fallback")
                        .tag("consumer", "tools")
                        .counter()
                        .count())
                .isZero();
        assertThat(requestContext.currentScopeAddedToolNames()).isEmpty();
    }

    /**
     * ADR-0068 §2: the gated set names every facade the engine may add that has a seeded {@code
     * mcp_tool_permission} row, beside the ranked candidates. {@code ExaWebSearchTool} is deliberately
     * NOT here, as in {@code ToolSelectionEngineTest}: it has no {@code mcp_tool} row, so production
     * never returns it in the gated set, and web search is offered through the engine's exemption.
     */
    private static ToolRegistryService.CandidateSelection gated(List<ToolMetadata> candidates) {
        Set<String> names = new java.util.HashSet<>(
                Set.of("DateWindowFacadeTool", "GlossaryFacadeTool", "InventoryFacadeTool", "OrderFacadeTool"));
        candidates.forEach(candidate -> names.add(candidate.name()));
        return new ToolRegistryService.CandidateSelection(candidates, names, false);
    }
}
