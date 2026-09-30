package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.tools.DateWindowFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.GlossaryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.scopegraph.ScopeResolver;
import com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import com.positivity.mcp.internal.service.ToolRegistryService;
import com.positivity.mcp.internal.service.ToolRegistryService.CandidateSelection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private GlossaryFacadeTool glossaryFacadeTool;
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
        when(toolRegistry.resolveMasterTools()).thenReturn(List.of(exaWebSearchTool));
        glossaryFacadeTool = new GlossaryFacadeTool();
        toolSelectionEngine = new ToolSelectionEngine(
                toolRegistry,
                dateWindowFacadeTool,
                glossaryFacadeTool,
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
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of(inventoryTool)));
        when(toolRegistry.resolveToolsByName(List.of("inventoryFacadeTool"))).thenReturn(List.of(inventoryFacadeTool));

        ToolSelectionEngine.ToolSelectionResult result =
                toolSelectionEngine.selectRoleTools("ROLE_ADMIN", PERMISSION_CODES, "show stock for sku ABC");

        assertThat(result.roleTools()).containsExactly(inventoryFacadeTool);
        assertThat(result.fallbackTools()).containsExactly(exaWebSearchTool, glossaryFacadeTool, inventoryFacadeTool);

        ArgumentCaptor<ToolSelectionContext> contextCaptor = ArgumentCaptor.forClass(ToolSelectionContext.class);
        verify(toolRegistryService).resolveCandidateSelection(contextCaptor.capture(), eq(3));
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
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of()));

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
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of()));

        for (String question : List.of(
                "revenue over the last twelve months",
                "how did we do this quarter",
                "invoices issued during the last six months",
                "sales year to date",
                "reopened workorders this week",
                "what did we take yesterday",
                "spend since April",
                // A pasted or multi-paragraph question is ordinary in a chat surface, and the
                // String.matches guard this replaced could not match a single word across a line
                // break at all: it anchors the whole input, and without DOTALL "." excludes "\n".
                "Hi,\nwhat was revenue last month?",
                "Two things:\n\n1. this quarter's totals\n2. anything overdue")) {
            assertThat(toolSelectionEngine
                            .selectRoleTools("ROLE_ADMIN", PERMISSION_CODES, question)
                            .fallbackTools())
                    .as("date-window tool offered for \"%s\"", question)
                    .contains(dateWindowFacadeTool);
        }
    }

    /**
     * #1840. The DATE_WINDOW layer gives a windowless report question the contract's default window
     * and sends the model to the resolver for it, so a metric question is dated even when it names
     * no unit. "Who are our ten largest customers by revenue?" lost the resolver to the candidate
     * cut on the 2026-09-06 sequences run and the model called the tool it could not see.
     */
    @Test
    @DisplayName("selectRoleTools offers resolveDateWindow for a metric question that names no window (#1840)")
    void selectRoleTools_offersTheDateWindowToolForAWindowlessMetricQuestion() {
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of()));

        for (String question : List.of(
                "Who are our ten largest customers by revenue?",
                "How much did we spend with Cascade Auto Warehouse",
                "what is our average time from work order creation to invoice",
                "rank our vendors by total billed",
                "which technician has the most completed work orders")) {
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
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of()));

        // Near misses, not just an obviously undated question: each of these was pulled in by a
        // token the set used to carry. "recent"/"recently"/"lately" are the very phrases the
        // DATE_WINDOW layer singles out as having no conventional reading and tells the model to ask
        // about, so offering a resolver for them is incoherent; "period" is accounting vocabulary far
        // more often than a window. A tool schema is ~440 tokens, so a false positive is a real cost,
        // not a rounding error — this is the bound on how broad the set may grow.
        for (String question : List.of(
                "what is the phone number for NAPA",
                "show me recent notes on this vehicle",
                "has anything odd happened lately",
                "which parts are on a periodic maintenance schedule",
                "post this to the open accounting period")) {
            assertThat(toolSelectionEngine
                            .selectRoleTools("ROLE_ADMIN", PERMISSION_CODES, question)
                            .fallbackTools())
                    .as("date-window tool withheld from \"%s\"", question)
                    .doesNotContain(dateWindowFacadeTool);
        }
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
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of()));

        toolSelectionEngine.selectRoleTools(
                "ROLE_ADMIN", PERMISSION_CODES, "create PO for vendor NAPA with two line items");

        ArgumentCaptor<ToolSelectionContext> contextCaptor = ArgumentCaptor.forClass(ToolSelectionContext.class);
        verify(toolRegistryService).resolveCandidateSelection(contextCaptor.capture(), eq(3));
        assertThat(contextCaptor.getValue().workflowState()).isEqualTo("CREATING_PO");
    }

    @Test
    @DisplayName("empty gated set keeps the master and glossary fallbacks but no role tools (#1606, ADR-0068 §2)")
    void selectRoleTools_emptyGatedSet_keepsUngatedFallbacksOnly() {
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(CandidateSelection.EMPTY);

        ToolSelectionEngine.ToolSelectionResult result =
                toolSelectionEngine.selectRoleTools("ROLE_ADMIN", PERMISSION_CODES, "latest internet sales report");

        // #1606: roleTools fail closed on an empty gated set — the ungated domain set is no longer
        // substituted. ADR-0068 §2: the keyword tags fire (web, orders, date window) but a caller who
        // holds no permission group for any tool is offered none of the tag-added facades either; the
        // master tools and the glossary (no permission row, no HTTP call) still populate.
        assertThat(result.roleTools()).isEmpty();
        assertThat(result.fallbackTools()).containsExactlyInAnyOrder(exaWebSearchTool, glossaryFacadeTool);
    }

    @Test
    @DisplayName("ADR-0068 §2: a tag-added facade the caller's gated set lacks is withheld")
    void selectRoleTools_withholdsTagAddedFacadesOutsideTheGatedSet() {
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        // The caller may use the inventory tool but not the order or date-window facades.
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(new CandidateSelection(List.of(), Set.of("InventoryFacadeTool"), false));

        ToolSelectionEngine.ToolSelectionResult result = toolSelectionEngine.selectRoleTools(
                "ROLE_ADMIN", PERMISSION_CODES, "how many sales orders for sku 4411 did we ship last month");

        assertThat(result.fallbackTools())
                .containsExactlyInAnyOrder(exaWebSearchTool, glossaryFacadeTool, inventoryFacadeTool)
                .doesNotContain(orderFacadeTool, dateWindowFacadeTool);
    }

    @Test
    @DisplayName("ADR-0068 §2: without a ToolRegistryService the gated set is unknown and only the glossary is added")
    void selectRoleTools_noRegistryService_addsOnlyTheGlossary() {
        ToolSelectionEngine unwired = new ToolSelectionEngine(
                toolRegistry,
                dateWindowFacadeTool,
                glossaryFacadeTool,
                exaWebSearchTool,
                inventoryFacadeTool,
                orderFacadeTool,
                null,
                sharedOrchestrationSupport,
                3);
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN")).thenReturn(new ArrayList<>(List.of(orderFacadeTool)));

        ToolSelectionEngine.ToolSelectionResult result =
                unwired.selectRoleTools("ROLE_ADMIN", PERMISSION_CODES, "sales revenue last month by store");

        assertThat(result.fallbackTools()).containsExactlyInAnyOrder(exaWebSearchTool, glossaryFacadeTool);
    }

    @Test
    @DisplayName("ADR-0068 §2: when the ranked path fails closed nothing tag-driven is added")
    void selectRoleTools_gatingQueryThrows_addsOnlyTheGlossary() {
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN")).thenReturn(new ArrayList<>(List.of(orderFacadeTool)));
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenThrow(new IllegalStateException("bad SQL grammar [permission_group]"));

        ToolSelectionEngine.ToolSelectionResult result = toolSelectionEngine.selectRoleTools(
                "ROLE_ADMIN", PERMISSION_CODES, "sales revenue last month by store");

        assertThat(result.roleTools()).isEmpty();
        assertThat(result.fallbackTools()).containsExactlyInAnyOrder(exaWebSearchTool, glossaryFacadeTool);
    }

    @Test
    @DisplayName("ADR-0068: QuestionTags.none() (warm-up, an absent record) selects exactly as mode off")
    void selectRoleTools_noneTags_behavesAsOff() {
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN")).thenReturn(new ArrayList<>(List.of(orderFacadeTool)));
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of()));
        String message = "create po for sales last month";

        ToolSelectionEngine.ToolSelectionResult withNone =
                toolSelectionEngine.selectRoleTools("ROLE_ADMIN", PERMISSION_CODES, message, QuestionTags.none());
        ToolSelectionEngine.ToolSelectionResult off =
                toolSelectionEngine.selectRoleTools("ROLE_ADMIN", PERMISSION_CODES, message);

        assertThat(withNone.workflowState())
                .isEqualTo(WorkflowState.CREATING_PO)
                .isEqualTo(off.workflowState());
        assertThat(withNone.fallbackTools())
                .containsExactlyElementsOf(off.fallbackTools())
                .contains(dateWindowFacadeTool, orderFacadeTool, glossaryFacadeTool);
        ArgumentCaptor<ToolSelectionContext> contextCaptor = ArgumentCaptor.forClass(ToolSelectionContext.class);
        verify(toolRegistryService, org.mockito.Mockito.times(2))
                .resolveCandidateSelection(contextCaptor.capture(), eq(3));
        assertThat(contextCaptor.getAllValues())
                .allMatch(context -> context.workflowState().equals("CREATING_PO"));
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
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of()));

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
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
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
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of(ghost)));
        when(toolRegistry.resolveToolsByName(List.of("ghostFacadeTool"))).thenReturn(List.of());

        ToolSelectionEngine.ToolSelectionResult result =
                toolSelectionEngine.selectRoleTools("ROLE_ADMIN", PERMISSION_CODES, "show stock for sku ABC");

        assertThat(result.roleTools()).isEmpty();
    }

    @Test
    @DisplayName("the glossary tool is offered for a message matching no keyword at all (#1688)")
    void fallbackTools_offersGlossaryWithNoKeywordMatch() {
        // The design decision this pins: the phrases that most need the glossary are the ones NOT in
        // it, which no glossary-derived keyword could match. Re-adding a keyword guard would keep the
        // other selection tests green, so this is the one that would fail.
        ToolSelectionEngine.ToolSelectionResult result =
                toolSelectionEngine.selectRoleTools("ROLE_USER", Set.of(), "who are our most loyal customers");

        assertThat(result.fallbackTools()).contains(glossaryFacadeTool);
    }

    @ParameterizedTest
    @DisplayName("a question naming an absolute period reaches the date-window resolver (#1684)")
    @ValueSource(
            strings = {
                "what did we spend with Michelin in 2025?",
                "revenue for Q3 2026",
                "sales in July 2026",
                "how much did we bill in December?"
            })
    void fallbackTools_offersDateWindowForANamedPeriod(String message) {
        // Removing the `period` shortcut made a resolver call mandatory before any dated report call,
        // and ReportingPeriods now rejects a missing range by telling the model to call
        // resolveNamedPeriod. If the tool is not offered for exactly this wording, that instruction
        // names a tool the model does not have and the turn dead-ends.
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of()));
        ToolSelectionEngine.ToolSelectionResult result =
                toolSelectionEngine.selectRoleTools("ROLE_USER", Set.of(), message);

        assertThat(result.fallbackTools()).contains(dateWindowFacadeTool);
    }

    @Test
    @DisplayName("fullFallbackTools stays a superset of the unconditional keyword-path additions")
    void fullFallbackTools_isASupersetOfTheUnconditionalAdditions() {
        // The role-level agent builds from fullFallbackTools before a question exists, while the
        // DATE_WINDOW and GLOSSARY prompt layers are appended unconditionally. A tool added
        // unconditionally to the keyword path but missing here hands that agent a prompt instructing
        // it to call a tool it does not have.
        assertThat(toolSelectionEngine.fullFallbackTools()).contains(dateWindowFacadeTool, glossaryFacadeTool);
    }

    // ── ADR-0069 §5 / §9: the scope is resolved here, and changes nothing ───

    private static final Set<String> SCOPE_CODES =
            Set.of("AUTHENTICATED", ScopeResolverFixtures.WORKORDER_VIEW, ScopeResolverFixtures.LOCATION_READ);

    private static final String SCOPE_MESSAGE = "is the work order WO-20391 in stock at the store?";

    private void stubSelection() {
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
        when(toolRegistryService.resolveCandidateSelection(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(gated(List.of(inventoryTool)));
        when(toolRegistry.resolveToolsByName(List.of("inventoryFacadeTool"))).thenReturn(List.of(inventoryFacadeTool));
    }

    /** Every facade this engine may add is in the caller's gated set, beside the ranked candidates. */
    private static CandidateSelection gated(List<ToolMetadata> candidates) {
        Set<String> names = new java.util.HashSet<>(Set.of(
                "DateWindowFacadeTool",
                "ExaWebSearchTool",
                "GlossaryFacadeTool",
                "InventoryFacadeTool",
                "OrderFacadeTool"));
        candidates.forEach(candidate -> names.add(candidate.name()));
        return new CandidateSelection(candidates, names, false);
    }

    private static ScopeResolver scopeResolver(ScopeGraphProperties properties) {
        return ScopeResolverFixtures.resolver(properties, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("ADR-0069: with no resolver wired (every hand-built engine) the result carries no scope")
    void noResolver_noScope() {
        stubSelection();

        assertThat(toolSelectionEngine
                        .selectRoleTools("ROLE_ADMIN", SCOPE_CODES, SCOPE_MESSAGE)
                        .scope())
                .isNull();
        // The older result constructors, which many tests build by hand, carry no scope either.
        assertThat(new ToolSelectionEngine.ToolSelectionResult(List.of(), List.of()).scope())
                .isNull();
        assertThat(new ToolSelectionEngine.ToolSelectionResult(List.of(), List.of(), WorkflowState.CREATING_PO).scope())
                .isNull();
    }

    @Test
    @DisplayName("ADR-0069: in mode off a wired resolver resolves nothing and the result carries no scope")
    void off_noScope() {
        stubSelection();
        ScopeResolver off = org.mockito.Mockito.spy(scopeResolver(ScopeGraphProperties.off()));
        toolSelectionEngine.setScopeResolver(off);

        ToolSelectionEngine.ToolSelectionResult result =
                toolSelectionEngine.selectRoleTools("ROLE_ADMIN", SCOPE_CODES, SCOPE_MESSAGE);

        assertThat(result.scope()).isNull();
        verify(off, org.mockito.Mockito.never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("ADR-0069: shadow resolves the turn's scope with the turn's workflow state, before tool ranking")
    void shadow_resolvesScopeAfterWorkflowStateAndBeforeRanking() {
        stubSelection();
        ScopeResolver shadow = org.mockito.Mockito.spy(scopeResolver(ScopeResolverFixtures.shadow(60)));
        toolSelectionEngine.setScopeResolver(shadow);

        ToolSelectionEngine.ToolSelectionResult result = toolSelectionEngine.selectRoleTools(
                "ROLE_ADMIN", SCOPE_CODES, SCOPE_MESSAGE, WorkflowState.CREATING_PO);

        assertThat(result.scope()).isNotNull();
        assertThat(result.scope().confidence()).isEqualTo(ScopeSet.Confidence.HIGH);
        assertThat(result.scope().seeds()).extracting(ScopeSet.Seed::entity).containsExactly("workorder");
        // WorkorderFacadeTool is valid in IDLE only, so the CREATING_PO turn's scope has no facade.
        assertThat(result.scope().facadeTools()).isEmpty();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(shadow, toolRegistryService);
        order.verify(shadow).resolve(SCOPE_MESSAGE, SCOPE_CODES, WorkflowState.CREATING_PO);
        order.verify(toolRegistryService).resolveCandidateSelection(any(ToolSelectionContext.class), eq(3));
    }

    @Test
    @DisplayName("ADR-0069: the session-less overload resolves the scope with the workflow state it derived")
    void shadow_sessionLessOverloadUsesTheDerivedWorkflowState() {
        ScopeResolver shadow = org.mockito.Mockito.spy(scopeResolver(ScopeResolverFixtures.shadow(60)));
        toolSelectionEngine.setScopeResolver(shadow);

        toolSelectionEngine.selectRoleTools("ROLE_USER", SCOPE_CODES, "create a purchase order for the work order");

        verify(shadow).resolve("create a purchase order for the work order", SCOPE_CODES, WorkflowState.CREATING_PO);
    }

    @ParameterizedTest
    @DisplayName("ADR-0069: shadow and enforce leave the selected tools exactly what they are in off")
    @ValueSource(strings = {"SHADOW", "ENFORCE"})
    void scopeDoesNotChangeSelection(String mode) {
        stubSelection();
        ToolSelectionEngine.ToolSelectionResult off =
                toolSelectionEngine.selectRoleTools("ROLE_ADMIN", SCOPE_CODES, SCOPE_MESSAGE);

        toolSelectionEngine.setScopeResolver(scopeResolver(new ScopeGraphProperties(
                ScopeGraphProperties.Mode.valueOf(mode),
                // Even with every consumer listed, nothing acts on the scope in this wave.
                List.of(ScopeGraphProperties.Consumer.values()),
                60,
                8,
                400)));
        ToolSelectionEngine.ToolSelectionResult on =
                toolSelectionEngine.selectRoleTools("ROLE_ADMIN", SCOPE_CODES, SCOPE_MESSAGE);

        // The scope really was resolved, and holds tools the ranked selection does not.
        assertThat(on.scope()).isNotNull();
        assertThat(on.scope().toolNames()).contains("WorkorderFacadeTool");
        assertThat(on.roleTools()).containsExactlyElementsOf(off.roleTools());
        assertThat(on.fallbackTools()).containsExactlyElementsOf(off.fallbackTools());
        assertThat(on.workflowState()).isEqualTo(off.workflowState());
        assertThat(sharedOrchestrationSupport.toolCacheKey(
                        sharedOrchestrationSupport.mergeTools(on.roleTools(), on.fallbackTools())))
                .isEqualTo(sharedOrchestrationSupport.toolCacheKey(
                        sharedOrchestrationSupport.mergeTools(off.roleTools(), off.fallbackTools())));
        // The ranking was asked the same question both times.
        ArgumentCaptor<ToolSelectionContext> contexts = ArgumentCaptor.forClass(ToolSelectionContext.class);
        verify(toolRegistryService, org.mockito.Mockito.times(2)).resolveCandidateSelection(contexts.capture(), eq(3));
        assertThat(contexts.getAllValues().get(1))
                .isEqualTo(contexts.getAllValues().get(0));
    }

    @Test
    @DisplayName("ADR-0069: a resolver that throws costs the turn its scope and nothing else")
    void resolverFailure_selectionUnchanged() {
        stubSelection();
        ToolSelectionEngine.ToolSelectionResult off =
                toolSelectionEngine.selectRoleTools("ROLE_ADMIN", SCOPE_CODES, SCOPE_MESSAGE);
        ScopeResolver broken = org.mockito.Mockito.mock(ScopeResolver.class);
        when(broken.enabled()).thenReturn(true);
        when(broken.resolve(any(), any(), any())).thenThrow(new IllegalStateException("resolver exploded"));
        toolSelectionEngine.setScopeResolver(broken);

        ToolSelectionEngine.ToolSelectionResult result =
                toolSelectionEngine.selectRoleTools("ROLE_ADMIN", SCOPE_CODES, SCOPE_MESSAGE);

        assertThat(result.scope()).isNull();
        assertThat(result.roleTools()).containsExactlyElementsOf(off.roleTools());
        assertThat(result.fallbackTools()).containsExactlyElementsOf(off.fallbackTools());
    }
}
