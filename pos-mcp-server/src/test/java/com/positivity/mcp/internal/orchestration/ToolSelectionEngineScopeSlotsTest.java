package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.ScopeGraphProperties.Consumer;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.tools.DateWindowFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.GlossaryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.scopegraph.ScopeResolverFixtures;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import com.positivity.mcp.internal.service.ToolRegistryService;
import com.positivity.mcp.internal.service.ToolRegistryService.CandidateSelection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

/**
 * ADR-0069 §6, facade slots in the shared selection engine: added on top of the ranked cut, never
 * displacing it, gated by the caller's gated set, capped, and silent on every path that adds nothing.
 */
@ExtendWith(MockitoExtension.class)
class ToolSelectionEngineScopeSlotsTest {

    /** Seeds workorder (HIGH); its scope holds WorkorderFacadeTool and, through billed_by, InvoiceFacadeTool. */
    private static final String MESSAGE = "is work order WO-20391 billed yet?";

    private static final Set<String> CALLER = Set.of(
            "AUTHENTICATED",
            ScopeResolverFixtures.WORKORDER_VIEW,
            ScopeResolverFixtures.INVOICE_VIEW,
            "inventory:stock:view");

    /** Stand-ins whose simple names are the fixture graph's facade tool names. */
    static final class WorkorderFacadeTool {}

    static final class InvoiceFacadeTool {}

    @Mock
    private MasterAgentRegistry toolRegistry;

    @Mock
    private ToolRegistryService toolRegistryService;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final WorkorderFacadeTool workorderFacadeTool = new WorkorderFacadeTool();
    private final InvoiceFacadeTool invoiceFacadeTool = new InvoiceFacadeTool();
    private InventoryFacadeTool inventoryFacadeTool;
    private ToolSelectionEngine engine;

    @BeforeEach
    void setUp() {
        inventoryFacadeTool = new InventoryFacadeTool(
                RestClient.builder(),
                "http://api-gateway",
                "/inventory/v1/inventory/stock/{sku}",
                "/inventory/v1/inventory/search?q={query}",
                "/inventory/v1/inventory/locations/{locationId}/stock",
                "/inventory/v1/inventory/replenishment/policies");
        lenient().when(toolRegistry.resolveMasterTools()).thenReturn(List.of());
        lenient().when(toolRegistry.resolveDomainTools(any())).thenAnswer(inv -> new ArrayList<>());
        lenient().when(toolRegistry.resolveToolsByName(anyCollection())).thenAnswer(invocation -> {
            Collection<String> names = invocation.getArgument(0);
            List<Object> beans = new ArrayList<>();
            for (String name : names) {
                switch (name) {
                    case "inventoryFacadeTool" -> beans.add(inventoryFacadeTool);
                    case "WorkorderFacadeTool" -> beans.add(workorderFacadeTool);
                    case "InvoiceFacadeTool" -> beans.add(invoiceFacadeTool);
                    default -> {
                        // no bean
                    }
                }
            }
            return beans;
        });
        engine = new ToolSelectionEngine(
                toolRegistry,
                new DateWindowFacadeTool(Clock.systemUTC()),
                new GlossaryFacadeTool(),
                new ExaWebSearchTool(RestClient.builder(), "https://api.exa.ai", "", "auto", 5),
                inventoryFacadeTool,
                new OrderFacadeTool(
                        RestClient.builder(),
                        "http://api-gateway",
                        "/order/v1/orders/{orderId}",
                        "/order/v1/orders/search?q={query}",
                        "/order/v1/orders/purchase-orders",
                        "/order/v1/orders/purchase-orders/{poId}",
                        "/order/v1/orders/purchase-orders/summary"),
                toolRegistryService,
                new SharedOrchestrationSupport(Clock.systemUTC()),
                3);
    }

    private void enforceTools(int slots) {
        ScopeGraphProperties properties = ScopeResolverFixtures.enforce(60, slots, 0, Consumer.TOOLS);
        engine.setScopeResolver(ScopeResolverFixtures.resolver(properties, meters));
        engine.setScopeConsumers(ScopeResolverFixtures.consumers(properties, meters));
    }

    private void rankedCut(Set<String> gated, boolean fastPath, ToolMetadata... ranked) {
        when(toolRegistryService.resolveCandidateSelection(
                        any(ToolSelectionContext.class), eq(3), any(QuestionTags.class)))
                .thenReturn(new CandidateSelection(List.of(ranked), gated, fastPath));
    }

    @Test
    @DisplayName("scope facades are appended after the ranked cut, which stays a prefix in its own order")
    void appendsAfterTheRankedCut() {
        enforceTools(8);
        rankedCut(Set.of("inventoryFacadeTool", "WorkorderFacadeTool", "InvoiceFacadeTool"), false, inventory());

        ToolSelectionEngine.ToolSelectionResult result = engine.selectRoleTools("ROLE_ADMIN", CALLER, MESSAGE);

        // Slot order: WorkorderFacadeTool is hop 1, InvoiceFacadeTool hop 2.
        assertThat(result.roleTools()).containsExactly(inventoryFacadeTool, workorderFacadeTool, invoiceFacadeTool);
        assertThat(result.scopeAddedTools()).containsExactly("WorkorderFacadeTool", "InvoiceFacadeTool");
        assertThat(result.scope()).isNotNull();
        assertThat(result.scope().confidence()).isEqualTo(ScopeSet.Confidence.HIGH);
    }

    @Test
    @DisplayName("the cap holds, in slot order, and a tool already ranked takes no slot")
    void capHoldsAndRankedToolsTakeNoSlot() {
        enforceTools(1);
        rankedCut(Set.of("inventoryFacadeTool", "WorkorderFacadeTool", "InvoiceFacadeTool"), false, inventory());

        ToolSelectionEngine.ToolSelectionResult one = engine.selectRoleTools("ROLE_ADMIN", CALLER, MESSAGE);
        assertThat(one.roleTools()).containsExactly(inventoryFacadeTool, workorderFacadeTool);
        assertThat(one.scopeAddedTools()).containsExactly("WorkorderFacadeTool");

        // WorkorderFacadeTool already ranked: the one slot goes to the next in order.
        rankedCut(
                Set.of("inventoryFacadeTool", "WorkorderFacadeTool", "InvoiceFacadeTool"),
                false,
                inventory(),
                facade("WorkorderFacadeTool"));
        ToolSelectionEngine.ToolSelectionResult next = engine.selectRoleTools("ROLE_ADMIN", CALLER, MESSAGE);
        assertThat(next.roleTools()).containsExactly(inventoryFacadeTool, workorderFacadeTool, invoiceFacadeTool);
        assertThat(next.scopeAddedTools()).containsExactly("InvoiceFacadeTool");
    }

    @Test
    @DisplayName("the gated set decides: a scope tool the caller may not use is never added")
    void gatedSetDecides() {
        enforceTools(8);
        // The SQL gate knows nothing of InvoiceFacadeTool for this caller, whatever the scope says.
        rankedCut(Set.of("inventoryFacadeTool", "WorkorderFacadeTool"), false, inventory());

        ToolSelectionEngine.ToolSelectionResult result = engine.selectRoleTools("ROLE_ADMIN", CALLER, MESSAGE);

        assertThat(result.roleTools()).containsExactly(inventoryFacadeTool, workorderFacadeTool);
        assertThat(result.scopeAddedTools()).containsExactly("WorkorderFacadeTool");
    }

    @Test
    @DisplayName(
            "nothing is added on the fail-closed paths: empty ranked cut, resolution to no beans, or a throwing gate")
    void nothingOnFailClosedPaths() {
        enforceTools(8);

        rankedCut(Set.of("WorkorderFacadeTool"), false);
        ToolSelectionEngine.ToolSelectionResult empty = engine.selectRoleTools("ROLE_ADMIN", CALLER, MESSAGE);
        assertThat(empty.roleTools()).isEmpty();
        assertThat(empty.scopeAddedTools()).isEmpty();

        when(toolRegistry.resolveDomainTools("ROLE_ADMIN")).thenReturn(new ArrayList<>(List.of(inventoryFacadeTool)));
        rankedCut(Set.of("WorkorderFacadeTool", "ghostTool"), false, facade("ghostTool"));
        ToolSelectionEngine.ToolSelectionResult noBeans = engine.selectRoleTools("ROLE_ADMIN", CALLER, MESSAGE);
        assertThat(noBeans.roleTools()).isEmpty();
        assertThat(noBeans.scopeAddedTools()).isEmpty();

        when(toolRegistryService.resolveCandidateSelection(
                        any(ToolSelectionContext.class), eq(3), any(QuestionTags.class)))
                .thenThrow(new IllegalStateException("gate unavailable"));
        ToolSelectionEngine.ToolSelectionResult threw = engine.selectRoleTools("ROLE_ADMIN", CALLER, MESSAGE);
        assertThat(threw.roleTools()).isEmpty();
        assertThat(threw.scopeAddedTools()).isEmpty();
    }

    @Test
    @DisplayName("the admin fast path keeps AdminFacadeTool alone")
    void nothingOnTheAdminFastPath() {
        enforceTools(8);
        Object adminFacadeTool = new Object();
        when(toolRegistry.resolveToolsByName(List.of("AdminFacadeTool"))).thenReturn(List.of(adminFacadeTool));
        rankedCut(Set.of("AdminFacadeTool", "WorkorderFacadeTool"), true, facade("AdminFacadeTool"));

        ToolSelectionEngine.ToolSelectionResult result = engine.selectRoleTools("ROLE_ADMIN", CALLER, MESSAGE);

        assertThat(result.roleTools()).containsExactly(adminFacadeTool);
        assertThat(result.scopeAddedTools()).isEmpty();
    }

    @Test
    @DisplayName("warm-up (the role name as the message) resolves NONE and adds nothing")
    void nothingOnWarmUp() {
        enforceTools(8);
        rankedCut(Set.of("inventoryFacadeTool"), false, inventory());

        ToolSelectionEngine.ToolSelectionResult result =
                engine.selectRoleTools("ROLE_ADMIN", CALLER, "ROLE_ADMIN", QuestionTags.none());

        assertThat(result.scope()).isNotNull();
        assertThat(result.scope().confidence()).isEqualTo(ScopeSet.Confidence.NONE);
        assertThat(result.roleTools()).containsExactly(inventoryFacadeTool);
        assertThat(result.scopeAddedTools()).isEmpty();
        // ADR-0068 §2: the one resolution always returns the gated set (the same SQL as before); a
        // NONE scope adds nothing to it.
        verify(toolRegistryService)
                .resolveCandidateSelection(any(ToolSelectionContext.class), eq(3), any(QuestionTags.class));
    }

    @Test
    @DisplayName("LOW confidence still adds (the slots are additive); the tools consumer acts on HIGH and LOW")
    void lowConfidenceAdds() {
        enforceTools(8);
        rankedCut(Set.of("inventoryFacadeTool", "WorkorderFacadeTool", "InvoiceFacadeTool"), false, inventory());

        // "ticket" denotes workorder and invoice: an ambiguous term, LOW.
        ToolSelectionEngine.ToolSelectionResult result =
                engine.selectRoleTools("ROLE_ADMIN", CALLER, "open the ticket");

        assertThat(result.scope().confidence()).isEqualTo(ScopeSet.Confidence.LOW);
        assertThat(result.scopeAddedTools()).containsExactly("InvoiceFacadeTool", "WorkorderFacadeTool");
    }

    @Test
    @DisplayName("not enforced (shadow, or enforce without tools): the ranked call is today's and nothing is added")
    void nothingWhenNotEnforced() {
        for (ScopeGraphProperties properties : List.of(
                ScopeResolverFixtures.shadow(60), ScopeResolverFixtures.enforce(60, Consumer.RAG, Consumer.CARD))) {
            SimpleMeterRegistry own = new SimpleMeterRegistry();
            engine.setScopeResolver(ScopeResolverFixtures.resolver(properties, own));
            engine.setScopeConsumers(ScopeResolverFixtures.consumers(properties, own));
            rankedCut(Set.of("inventoryFacadeTool", "WorkorderFacadeTool", "InvoiceFacadeTool"), false, inventory());

            ToolSelectionEngine.ToolSelectionResult result = engine.selectRoleTools("ROLE_ADMIN", CALLER, MESSAGE);

            assertThat(result.scope()).isNotNull();
            assertThat(result.scope().confidence()).isEqualTo(ScopeSet.Confidence.HIGH);
            assertThat(result.roleTools()).containsExactly(inventoryFacadeTool);
            assertThat(result.scopeAddedTools()).isEmpty();
        }
        // ADR-0068 §2: the gated set is fetched by the one resolution in every mode; not enforced
        // means the scope's slot step never runs on it.
        verify(toolRegistryService, never()).resolveCandidateTools(any(), eq(3));
    }

    private static ToolMetadata inventory() {
        return facade("inventoryFacadeTool");
    }

    private static ToolMetadata facade(String name) {
        return new ToolMetadata(
                UUID.randomUUID(), name, name, name + " description", "test", 1.0, "low", 200, true, name);
    }
}
