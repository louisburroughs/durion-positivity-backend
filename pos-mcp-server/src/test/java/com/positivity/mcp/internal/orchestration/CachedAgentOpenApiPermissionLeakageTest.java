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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.positivity.mcp.internal.classification.SimpleChatRuleDefaults;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.discovery.OperationProxyFactory;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.orchestration.rag.ScopedContentRetrieverFactory;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.internal.service.NltiWorkflowStateService;
import com.positivity.mcp.internal.service.OpenApiToolProvider;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.mcp.internal.service.RolePromptResolver;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Issue #1196 (Gate 3 closure, {@code docs/gate3-openapi-bridge-design.md}): proves that the
 * per-role cached agent does <em>not</em> leak OpenAPI-discovered tools across callers with
 * different permissions.
 *
 * <p>Chat agents are cached per role ({@code roleAgentCache}, TTL {@code
 * mcp.agent.cache-ttl-minutes}), so the exact same {@link PosAssistant} instance — holding the
 * exact same {@link OpenApiToolProvider} reference — serves every caller of that role until
 * eviction. The design defence is that OpenAPI tools are resolved per request from
 * {@link RequestScopedUserContext} (never captured at agent-build time). This test turns that
 * design claim into an executable proof through the REAL production seam:
 *
 * <ul>
 *   <li><strong>Warm-then-reuse:</strong> user A (holds permission P) chats first and warms the
 *       cached agent; user B (lacks P) chats second through the very same cached agent instance
 *       (asserted via the {@code roleAgentCache} key set staying a single entry).
 *   <li><strong>Not offered:</strong> the tool callbacks attached to each request's {@link Prompt}
 *       options are captured from the (mocked) {@link ChatModel}. A's prompt carries the P-gated
 *       discovered tool; B's prompt carries none.
 *   <li><strong>Not executable:</strong> Spring AI's tool-calling loop can only execute a
 *       {@link ToolCallback} present in the prompt's {@link ToolCallingChatOptions}. B's options
 *       contain zero OpenAPI callbacks, so there is nothing to execute — and a direct
 *       provider resolution under B's published context returns empty, confirming the execution
 *       path fails closed at the source as well.
 *   <li><strong>Selection layer:</strong> both the tool-selection engine call and the discovered-
 *       candidate gating query ({@code findDiscoveredCandidatesForPermissions}) are verified to
 *       receive each request's own permission codes — B's query runs with B's codes, not A's.
 *   <li><strong>Fail-closed default:</strong> with no {@link RequestScopedUserContext} wired (or
 *       none published), the provider yields zero discovered tools and the gating query is never
 *       even issued.
 *   <li><strong>No bleed-through:</strong> the manager clears the thread-local caller context in
 *       its {@code finally}, so a later request on a reused thread cannot inherit A's identity.
 * </ul>
 *
 * <p>The repository is mocked to emulate a seeded {@code mcp_tool} row ({@code source='openapi'})
 * gated on a single permission {@code P = accounting:invoice:view}: the candidate query returns
 * the operation only when the caller's permission codes contain P, mirroring the fail-closed SQL
 * filter (the live-SQL variant of that filter is covered by {@code OpenApiToolPermissionGatingIT},
 * which requires the pgvector-backed alpha stack — the candidate query uses pgvector operators and
 * cannot run on H2, which is why this proof is exercised at the orchestration-unit level).
 *
 * <p><strong>Remaining live-stack item (#1196):</strong> the streaming SSE variant
 * ({@code StreamingSessionAgentManager}) publishes/clears the same thread-local around
 * Flux-assembly-time callback resolution; a live end-to-end proof over the SSE transport on the
 * running alpha stack is still outstanding and tracked by issue #1196 — it is not covered here.
 */
@ExtendWith(MockitoExtension.class)
class CachedAgentOpenApiPermissionLeakageTest {

    private static final UUID USER_A_ID = UUID.fromString("00000000-0000-7000-8000-000000001196");
    private static final UUID USER_B_ID = UUID.fromString("00000000-0000-7000-8000-000000002196");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-07T02:00:00Z"), ZoneOffset.UTC);

    /** The single permission gating the seeded OpenAPI tool. */
    private static final String GATED_PERMISSION = "accounting:invoice:view";

    private static final String GATED_TOOL_NAME = "accounting_listinvoices";
    private static final String MESSAGE = "show open invoices for the accounting ledger";

    @Mock
    private ChatModel chatModel;

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
    private NltiWorkflowStateService workflowStateService;

    @Mock
    private ToolMetadataRepository toolMetadataRepository;

    @Mock
    private OperationProxyFactory proxyFactory;

    // Real collaborators: this test proves the production seam, so the request-scoped holder and
    // the OpenAPI provider must be the real classes, shared by every request the way the cached
    // agent shares them in production.
    private RequestScopedUserContext requestScopedUserContext;
    private OpenApiToolProvider openApiToolProvider;
    private SessionAgentManager manager;

    @BeforeEach
    void setUp() {
        // ChatClient (which runs the tool-execution loop) dereferences ChatModel.getOptions()
        // unconditionally, so a bare @Mock returning null options NPEs the whole chat path.
        // Real models always carry options; mirror that here.
        lenient()
                .when(chatModel.getOptions())
                .thenReturn(OllamaChatOptions.builder().model("test-model").build());
        requestScopedUserContext = new RequestScopedUserContext();
        requestScopedUserContext.clear(); // static ThreadLocal — start every test clean
        openApiToolProvider = new OpenApiToolProvider(
                toolMetadataRepository,
                embeddingModel,
                requestScopedUserContext,
                proxyFactory,
                new ObjectMapper(),
                8,
                Duration.ofSeconds(30),
                null);

        // Seeded discovered op (source='openapi'), gated on exactly one permission. The candidate
        // query is fail-closed: it returns the op only when the caller's codes contain that
        // permission — the in-memory analogue of the mcp_tool/mcp_tool_permission SQL filter.
        DiscoveredOperation gatedOp = new DiscoveredOperation(
                GATED_TOOL_NAME, "List invoices", "GET", "/v1/accounting/invoices", "pos-accounting", null, List.of());
        lenient()
                .when(toolMetadataRepository.findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), any()))
                .thenAnswer(invocation -> {
                    Set<String> callerCodes = invocation.getArgument(2);
                    return callerCodes.contains(GATED_PERMISSION) ? List.of(gatedOp) : List.of();
                });

        lenient().when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        when(toolRegistry.preloadableRoleIdentifiers()).thenReturn(Set.of());
        lenient().when(toolRegistry.resolveDomainTools(anyString())).thenAnswer(inv -> new ArrayList<>());
        lenient().when(toolRegistry.sharedTools()).thenReturn(List.of());
        lenient().when(toolRegistry.resolveRagScopeForTools(anyCollection())).thenReturn("master");
        lenient()
                .when(toolSelectionEngine.selectRoleTools(anyString(), anySet(), anyString()))
                .thenReturn(new ToolSelectionEngine.ToolSelectionResult(List.of(), List.of()));
        lenient().when(toolSelectionEngine.fullFallbackTools()).thenReturn(List.of());
        lenient().when(workflowStateService.resolveActiveState(anyString())).thenReturn(Optional.empty());
        lenient()
                .when(rolePromptResolver.assemble(anyString(), anyString(), anyBoolean()))
                .thenReturn(new RolePromptResolver.AssembledPrompt("prompt", List.of("BASE", "ROLE")));
        lenient().when(rolePromptResolver.resolvePrompt(anyString())).thenReturn("Default role prompt");
        QueryDocumentRetriever scopedRetriever = mock(QueryDocumentRetriever.class);
        lenient().when(scopedRetriever.retrieve(anyString())).thenReturn(List.of());
        lenient()
                .when(scopedContentRetrieverFactory.create(anyString(), anyInt(), anyDouble()))
                .thenReturn(scopedRetriever);
        lenient().when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("ok"));

        manager = buildManager(requestScopedUserContext);
    }

    @AfterEach
    void cleanup() {
        // The holder is a static ThreadLocal shared across instances — never let a test leak it.
        requestScopedUserContext.clear();
    }

    @Test
    @DisplayName("#1196: cached agent warmed by high-permission caller A neither offers nor can execute the "
            + "A-only openapi tool for low-permission caller B")
    void cachedAgentDoesNotLeakOpenApiToolAcrossCallers() {
        CurrentUserContext userA = caller("alice", USER_A_ID, Set.of("AUTHENTICATED", GATED_PERMISSION));
        CurrentUserContext userB = caller("bob", USER_B_ID, Set.of("AUTHENTICATED"));

        // --- User A warms the cached role agent and is offered the gated tool. ---
        manager.chat(userA, MESSAGE);
        // --- User B reuses the SAME cached agent; the tool must not follow. ---
        manager.chat(userB, MESSAGE);

        // Both requests hit one and the same cached agent (single role/tool cache entry).
        assertThat(roleAgentCacheKeys(manager)).hasSize(1);

        // Offered surface = the tool callbacks attached to each request's prompt options.
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(promptCaptor.capture());
        List<String> offeredToA = openApiToolNames(promptCaptor.getAllValues().get(0));
        List<String> offeredToB = openApiToolNames(promptCaptor.getAllValues().get(1));
        assertThat(offeredToA).containsExactly(GATED_TOOL_NAME);
        // Not offered: B's prompt carries no openapi callback at all — and because Spring AI can
        // only execute callbacks present in the prompt's options, B cannot execute it either.
        assertThat(offeredToB).isEmpty();

        // Gating query layer: each request queried with ITS OWN permission codes.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> codesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(toolMetadataRepository, times(2))
                .findDiscoveredCandidatesForPermissions(any(), anyInt(), codesCaptor.capture(), any());
        assertThat(codesCaptor.getAllValues().get(0)).contains(GATED_PERMISSION);
        assertThat(codesCaptor.getAllValues().get(1)).doesNotContain(GATED_PERMISSION);

        // Selection layer: role-tool selection also received per-request codes, not A's.
        verify(toolSelectionEngine).selectRoleTools(eq("ROLE_ADMIN"), eq(userA.permissionCodes()), eq(MESSAGE));
        verify(toolSelectionEngine).selectRoleTools(eq("ROLE_ADMIN"), eq(userB.permissionCodes()), eq(MESSAGE));

        // Execution fail-closed at the source: resolving under B's context on the shared provider
        // (exactly what the cached agent does mid-chat) yields nothing to execute.
        requestScopedUserContext.set(userB);
        assertThat(openApiToolProvider.resolveToolCallbacks(MESSAGE)).isEmpty();
        requestScopedUserContext.clear();

        // No bleed-through: the manager cleared the thread-local after each request, so a reused
        // thread cannot inherit a prior caller's identity.
        assertThat(requestScopedUserContext.current()).isEmpty();
    }

    @Test
    @DisplayName("#1196: manager without request-scoped context wiring fails closed — zero discovered tools, "
            + "gating query never issued")
    void failsClosedWhenNoRequestScopedContextWired() {
        SessionAgentManager unwiredManager = buildManager(null);
        CurrentUserContext userA = caller("alice", USER_A_ID, Set.of("AUTHENTICATED", GATED_PERMISSION));

        unwiredManager.chat(userA, MESSAGE);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        // Even a fully privileged caller gets no discovered tools when no caller context is
        // published: the provider read an empty holder and returned nothing.
        assertThat(openApiToolNames(promptCaptor.getValue())).isEmpty();
        verify(toolMetadataRepository, never()).findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("#1196: provider alone fails closed with an unpublished holder")
    void providerFailsClosedWithoutPublishedContext() {
        assertThat(openApiToolProvider.resolveToolCallbacks(MESSAGE)).isEmpty();
        verify(toolMetadataRepository, never()).findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), any());
    }

    private SessionAgentManager buildManager(RequestScopedUserContext contextOrNull) {
        return new SessionAgentManager(
                chatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                new SharedOrchestrationSupport(Clock.systemUTC()),
                toolSelectionEngine,
                scopedContentRetrieverFactory,
                null, // toolExecutionAuditLogger
                null, // sessionSummary
                rolePromptResolver,
                new SimpleChatFastPath(
                        new SimpleChatClassifier(SimpleChatRuleDefaults.defaultCatalog()),
                        rolePromptResolver,
                        new SharedOrchestrationSupport(Clock.systemUTC())),
                null, // telemetryEmitter
                openApiToolProvider,
                null, // answerResolutionLadder
                contextOrNull,
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

    /** Names of the OpenAPI-discovered tool callbacks attached to the prompt's tool-calling options. */
    private static List<String> openApiToolNames(Prompt prompt) {
        assertThat(prompt.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        List<ToolCallback> callbacks = ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks();
        return callbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private static CurrentUserContext caller(String username, UUID userId, Set<String> permissionCodes) {
        return new CurrentUserContext(
                username, userId, "ROLE_ADMIN", Set.of("ROLE_ADMIN"), Set.of("AUTHENTICATED"), permissionCodes);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> roleAgentCacheKeys(SessionAgentManager sessionAgentManager) {
        Cache<String, ?> cache = (Cache<String, ?>) ReflectionTestUtils.getField(sessionAgentManager, "roleAgentCache");
        assertThat(cache).isNotNull();
        cache.cleanUp();
        return cache.asMap().keySet();
    }
}
