package com.positivity.mcp.internal.orchestration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.classification.SimpleChatRuleDefaults;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.tools.DateWindowFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.GlossaryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import com.positivity.mcp.internal.repository.ToolPriorityRepository;
import com.positivity.mcp.internal.service.TenantToolPriorityResolver;
import com.positivity.mcp.internal.service.ToolRegistryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.web.client.RestClient;

/**
 * ADR-0068 (spec §4, behaviour preservation): the decision surfaces the session managers drive, so
 * the fixture in {@code tagging/decisions-fixture.json} (captured from the pre-refactor code) can be
 * checked against them. Every decision is read from the public result of the component that makes
 * it, never re-implemented here.
 *
 * <p>Since the refactor the decisions are read from the {@link HeuristicQuestionTagger} record, the
 * one place they are taken; the engine's own result is still checked for the workflow state it
 * selects on and the {@code ToolRegistryService} fast path for the admin decision. The keyword-added
 * facades are no longer read from the engine's fallback list: with no {@code ToolRegistryService}
 * the gated set is unavailable and, by ADR-0068 §2, nothing tag-driven is added.
 */
final class TaggingDecisionSurfaces {

    static final Set<String> PERMISSION_CODES = Set.of("AUTHENTICATED", "admin:user:view");
    static final String ROLE = "ROLE_ADMIN";
    static final int TOP_K = 8;
    static final int MAX_SUB_QUERIES = 3;

    /** The decisions the pre-ADR-0068 heuristics take on one message. */
    record Decisions(
            boolean simpleChat,
            boolean followsPreviousTurn,
            String workflowState,
            boolean impliesDateWindow,
            boolean needsWebSearch,
            boolean aboutInventory,
            boolean aboutOrders,
            boolean adminFastPath,
            boolean compoundQuestion) {}

    private final SimpleChatClassifier simpleChatClassifier;
    private final HeuristicQuestionTagger heuristicTagger;
    private final ToolSelectionEngine toolSelectionEngine;
    private final ToolRegistryService toolRegistryService;
    private final SharedOrchestrationSupport sharedOrchestrationSupport;

    TaggingDecisionSurfaces() {
        this.simpleChatClassifier = new SimpleChatClassifier(SimpleChatRuleDefaults.defaultCatalog());
        this.heuristicTagger = new HeuristicQuestionTagger(simpleChatClassifier, MAX_SUB_QUERIES);
        Clock clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC);
        this.sharedOrchestrationSupport = new SharedOrchestrationSupport(clock);
        MasterAgentRegistry masterAgentRegistry = mock(MasterAgentRegistry.class);
        when(masterAgentRegistry.resolveMasterTools()).thenReturn(List.of());
        when(masterAgentRegistry.resolveDomainTools(anyString())).thenReturn(List.of());
        // No ToolRegistryService: the engine derives the workflow state and the keyword-added tools
        // from the message alone, which is the surface under test here.
        this.toolSelectionEngine = new ToolSelectionEngine(
                masterAgentRegistry,
                new DateWindowFacadeTool(clock),
                new GlossaryFacadeTool(),
                new ExaWebSearchTool(RestClient.builder(), "https://api.exa.ai", "", "auto", 5),
                new InventoryFacadeTool(
                        RestClient.builder(),
                        "http://api-gateway",
                        "/inventory/v1/inventory/stock/{sku}",
                        "/inventory/v1/inventory/search?q={query}",
                        "/inventory/v1/inventory/locations/{locationId}/stock",
                        "/inventory/v1/inventory/replenishment/policies"),
                new OrderFacadeTool(
                        RestClient.builder(),
                        "http://api-gateway",
                        "/order/v1/orders/{orderId}",
                        "/order/v1/orders/search?q={query}",
                        "/order/v1/orders/purchase-orders",
                        "/order/v1/orders/purchase-orders/{poId}",
                        "/order/v1/orders/purchase-orders/summary"),
                null,
                sharedOrchestrationSupport,
                TOP_K);
        this.toolSelectionEngine.setHeuristicTagger(heuristicTagger);
        // The admin fast path decides on the gated set alone; a gated set of the admin tool and no
        // embeddings takes the priority fallback when the path does not fire.
        ToolMetadataRepository repository = mock(ToolMetadataRepository.class);
        ToolMetadata adminTool = new ToolMetadata(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "AdminFacadeTool",
                "Admin",
                "Administration",
                "admin",
                1.0,
                "low",
                50,
                true,
                "adminFacadeTool");
        when(repository.findEnabledByPermissionsAndWorkflow(any(), anyString())).thenReturn(List.of(adminTool));
        when(repository.findTopKByEmbeddingForPermissions(any(), anyInt(), any(), anyString()))
                .thenReturn(List.of());
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0f});
        this.toolRegistryService = new ToolRegistryService(
                repository, embeddingModel, new TenantToolPriorityResolver(mock(ToolPriorityRepository.class)));
    }

    /** The decisions as the tagged path in mode {@code off} takes them. */
    Decisions decide(String message) {
        QuestionTags tags = toolSelectionEngine.tag(message);
        ToolSelectionEngine.ToolSelectionResult selection =
                toolSelectionEngine.selectRoleTools(ROLE, PERMISSION_CODES, message, tags);
        boolean adminFastPath = toolRegistryService
                .resolveCandidateSelection(new ToolSelectionContext(message, ROLE, "IDLE", PERMISSION_CODES), TOP_K)
                .adminFastPath();
        List<String> fallback = sharedOrchestrationSupport.toolNames(selection.fallbackTools());
        if (!fallback.equals(List.of("GlossaryFacadeTool"))) {
            throw new AssertionError("gated set unavailable, only the glossary may be added: " + fallback);
        }
        return new Decisions(
                simpleChatFastPath().isSimpleChat(message, tags),
                tags.followsPreviousTurn(),
                selection.workflowState().name(),
                tags.impliesDateWindow(),
                tags.needsWebSearch(),
                tags.aboutInventory(),
                tags.aboutOrders(),
                adminFastPath,
                tags.compoundQuestion());
    }

    /** The heuristic record alone, for asserting the tagger against the fixture directly. */
    QuestionTags heuristicTags(String message) {
        return heuristicTagger.tag(message);
    }

    /** True when {@code message} is simple chat by the pre-refactor classifier call. */
    boolean classifierSaysSimpleChat(String message) {
        return simpleChatClassifier.isSimpleChat(message);
    }

    private SimpleChatFastPath simpleChatFastPath() {
        return new SimpleChatFastPath(
                simpleChatClassifier,
                mock(com.positivity.mcp.internal.service.RolePromptResolver.class),
                sharedOrchestrationSupport);
    }
}
