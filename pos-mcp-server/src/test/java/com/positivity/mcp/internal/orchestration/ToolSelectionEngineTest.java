package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.tools.DateWindowFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.service.ToolRegistryService;
import java.time.Clock;
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
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class ToolSelectionEngineTest {

    private static final Set<String> PERMISSION_CODES = Set.of("AUTHENTICATED", "inventory:stock:view");

    @Mock
    private MasterAgentRegistry toolRegistry;

    @Mock
    private ToolRegistryService toolRegistryService;

    private DateWindowFacadeTool dateWindowFacadeTool;
    private ExaWebSearchTool exaWebSearchTool;
    private InventoryFacadeTool inventoryFacadeTool;
    private OrderFacadeTool orderFacadeTool;
    private SharedOrchestrationSupport sharedOrchestrationSupport;
    private ToolSelectionEngine toolSelectionEngine;

    @BeforeEach
    void setUp() {
        dateWindowFacadeTool = new DateWindowFacadeTool(Clock.systemUTC());
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
        sharedOrchestrationSupport = new SharedOrchestrationSupport(Clock.systemUTC());
        when(toolRegistry.resolveMasterTools()).thenReturn(List.of(exaWebSearchTool));
        toolSelectionEngine = new ToolSelectionEngine(
                toolRegistry,
                dateWindowFacadeTool,
                exaWebSearchTool,
                inventoryFacadeTool,
                orderFacadeTool,
                toolRegistryService,
                sharedOrchestrationSupport,
                3);
    }

    @Test
    @DisplayName("selectRoleTools keeps general lookup queries on the seeded IDLE workflow")
    void selectRoleTools_keepsGeneralLookupQueriesOnIdleWorkflowAndNarrowsRoleTools() {
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
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of(inventoryTool));
        when(toolRegistry.resolveToolsByName(List.of("inventoryFacadeTool"))).thenReturn(List.of(inventoryFacadeTool));

        ToolSelectionEngine.ToolSelectionResult result =
                toolSelectionEngine.selectRoleTools("ROLE_ADMIN", PERMISSION_CODES, "show stock for sku ABC");

        assertThat(result.roleTools()).containsExactly(inventoryFacadeTool);
        assertThat(result.fallbackTools()).containsExactly(exaWebSearchTool, inventoryFacadeTool);

        ArgumentCaptor<ToolSelectionContext> contextCaptor = ArgumentCaptor.forClass(ToolSelectionContext.class);
        verify(toolRegistryService).resolveCandidateTools(contextCaptor.capture(), eq(3));
        assertThat(contextCaptor.getValue().workflowState()).isEqualTo("IDLE");
        assertThat(contextCaptor.getValue().permissionCodes()).isEqualTo(PERMISSION_CODES);
    }

    /**
     * #1684. DateWindowFacadeTool's mcp_tool row (V43) carries domain {@code date-window}, which no
     * role resolves to, so its only route into the candidate set is the embedding ranking in
     * {@code ToolRegistryService.resolveCandidateTools} — where it competes with every other gated
     * tool on description similarity. Nothing in "which customers haven't bought in the last 90
     * days" reads as a date-arithmetic request, so the tool the DATE_WINDOW layer instructs the
     * model to call before every dated argument is exactly the tool most likely to be missing from
     * the set for a dated question. Without it the model has no option but to compute the dates
     * itself, which is the free-form path #1675 and #1684 exist to close.
     */
    @Test
    @DisplayName("selectRoleTools always offers resolveDateWindow for a dated question (#1684)")
    void selectRoleTools_alwaysOffersTheDateWindowToolForADatedQuestion() {
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of());

        ToolSelectionEngine.ToolSelectionResult result = toolSelectionEngine.selectRoleTools(
                "ROLE_ADMIN",
                PERMISSION_CODES,
                "which customers haven't bought in the last 90 days but spent over $10,000 in the prior year?");

        assertThat(result.fallbackTools()).contains(dateWindowFacadeTool);
    }

    /**
     * The keyword set has to cover the calendar units a question states its window in, not only the
     * word "date" — the gate's failing questions say "twelve months", "this quarter", "year to
     * date", never "date range".
     */
    @Test
    @DisplayName("selectRoleTools offers resolveDateWindow across the calendar-unit vocabulary")
    void selectRoleTools_offersTheDateWindowToolAcrossCalendarVocabulary() {
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of());

        for (String question : List.of(
                "revenue over the last twelve months",
                "how did we do this quarter",
                "invoices issued during the last six months",
                "sales year to date",
                "reopened workorders this week",
                "what did we take yesterday",
                "spend since April")) {
            assertThat(toolSelectionEngine
                            .selectRoleTools("ROLE_ADMIN", PERMISSION_CODES, question)
                            .fallbackTools())
                    .as("date-window tool offered for \"%s\"", question)
                    .contains(dateWindowFacadeTool);
        }
    }

    /**
     * The tool is additive rather than free: it costs a tool schema in every prompt it joins, so a
     * question that carries no window must not pull it in. This is the bound on how broad the
     * keyword set may grow.
     */
    @Test
    @DisplayName("selectRoleTools withholds resolveDateWindow from a question with no window")
    void selectRoleTools_withholdsTheDateWindowToolWhenNoWindowIsAsked() {
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of());

        ToolSelectionEngine.ToolSelectionResult result = toolSelectionEngine.selectRoleTools(
                "ROLE_ADMIN", PERMISSION_CODES, "what is the phone number for NAPA");

        assertThat(result.fallbackTools()).doesNotContain(dateWindowFacadeTool);
    }

    /**
     * The role-level agent paths build before a question exists, so there is no keyword to gate on
     * and the message-independent set must carry every keyword-addable tool. An agent warmed without
     * the resolver would carry a DATE_WINDOW layer requiring a tool it was never given.
     */
    @Test
    @DisplayName("fullFallbackTools carries every keyword-addable tool, resolveDateWindow included")
    void fullFallbackTools_carriesTheDateWindowTool() {
        assertThat(toolSelectionEngine.fullFallbackTools())
                .contains(dateWindowFacadeTool, exaWebSearchTool, inventoryFacadeTool, orderFacadeTool);
    }

    @Test
    @DisplayName("selectRoleTools derives seeded purchase-order workflow when query is explicitly PO creation")
    void selectRoleTools_derivesCreatingPoWorkflow() {
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of());

        toolSelectionEngine.selectRoleTools(
                "ROLE_ADMIN", PERMISSION_CODES, "create PO for vendor NAPA with two line items");

        ArgumentCaptor<ToolSelectionContext> contextCaptor = ArgumentCaptor.forClass(ToolSelectionContext.class);
        verify(toolRegistryService).resolveCandidateTools(contextCaptor.capture(), eq(3));
        assertThat(contextCaptor.getValue().workflowState()).isEqualTo("CREATING_PO");
    }

    @Test
    @DisplayName("empty gated set keeps keyword fallbacks but no role tools (#1606)")
    void selectRoleTools_emptyGatedSet_keepsKeywordFallbacksOnly() {
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of());

        ToolSelectionEngine.ToolSelectionResult result =
                toolSelectionEngine.selectRoleTools("ROLE_ADMIN", PERMISSION_CODES, "latest internet sales report");

        // #1606: roleTools now fail closed on an empty gated set — the ungated domain set is no
        // longer substituted. Keyword fallbacks are a separate list and still populate, so the
        // assistant keeps web search rather than going mute.
        assertThat(result.roleTools()).isEmpty();
        assertThat(result.fallbackTools()).containsExactly(exaWebSearchTool, orderFacadeTool);
    }

    @Test
    @DisplayName("empty gated set yields NO tools — never the ungated domain set (#1606)")
    void selectRoleTools_emptyGatedSet_failsClosed() {
        // resolveDomainTools is bucketed by domain with no permission gating, so returning it when
        // the gate legitimately matches nothing would hand a caller MORE tools than a successful
        // gate would. V40 makes this reachable: a caller holding only a code V40 strips from the
        // gate now matches no permission group.
        when(toolRegistry.resolveDomainTools("ROLE_TECHNICIAN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of());

        ToolSelectionEngine.ToolSelectionResult result =
                toolSelectionEngine.selectRoleTools("ROLE_TECHNICIAN", PERMISSION_CODES, "show me customer history");

        assertThat(result.roleTools()).isEmpty();
    }

    @Test
    @DisplayName("gating-query failure yields NO tools — fail closed, not back to role scope (#1608)")
    void selectRoleTools_gatingQueryThrows_failsClosed() {
        // Reachable in ordinary operation: a pod serving before Flyway applies V40 raises
        // BadSqlGrammarException on the permission_group column. Degrading to the role-scoped set
        // would silently revert authorisation from perm_bits to roles.
        when(toolRegistry.resolveDomainTools("ROLE_TECHNICIAN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenThrow(new IllegalStateException("bad SQL grammar [permission_group]"));

        ToolSelectionEngine.ToolSelectionResult result =
                toolSelectionEngine.selectRoleTools("ROLE_TECHNICIAN", PERMISSION_CODES, "show me customer history");

        assertThat(result.roleTools()).isEmpty();
    }

    @Test
    @DisplayName("names resolving to zero beans yields NO tools — a wiring fault must not widen the gate")
    void selectRoleTools_namesResolveToNoBeans_failsClosed() {
        ToolMetadata ghost = new ToolMetadata(
                UUID.randomUUID(),
                "ghostFacadeTool",
                "Ghost",
                "No such bean",
                "inventory",
                1.0,
                "low",
                200,
                true,
                "ghostFacadeTool");
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of(ghost));
        when(toolRegistry.resolveToolsByName(List.of("ghostFacadeTool"))).thenReturn(List.of());

        ToolSelectionEngine.ToolSelectionResult result =
                toolSelectionEngine.selectRoleTools("ROLE_ADMIN", PERMISSION_CODES, "show stock for sku ABC");

        assertThat(result.roleTools()).isEmpty();
    }
}
