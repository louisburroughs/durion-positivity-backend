package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.service.ToolRegistryService;
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

    private ExaWebSearchTool exaWebSearchTool;
    private InventoryFacadeTool inventoryFacadeTool;
    private OrderFacadeTool orderFacadeTool;
    private SharedOrchestrationSupport sharedOrchestrationSupport;
    private ToolSelectionEngine toolSelectionEngine;

    @BeforeEach
    void setUp() {
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
        when(toolRegistry.resolveMasterTools()).thenReturn(List.of(exaWebSearchTool));
        toolSelectionEngine = new ToolSelectionEngine(
                toolRegistry,
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
    @DisplayName("selectRoleTools falls back to full role tools and keyword fallback tools")
    void selectRoleTools_fallsBackToFullRoleToolsAndKeywordFallbacks() {
        when(toolRegistry.resolveDomainTools("ROLE_ADMIN"))
                .thenReturn(new ArrayList<>(List.of(orderFacadeTool, inventoryFacadeTool)));
        when(toolRegistryService.resolveCandidateTools(any(ToolSelectionContext.class), eq(3)))
                .thenReturn(List.of());

        ToolSelectionEngine.ToolSelectionResult result =
                toolSelectionEngine.selectRoleTools("ROLE_ADMIN", PERMISSION_CODES, "latest internet sales report");

        assertThat(result.roleTools()).containsExactly(orderFacadeTool, inventoryFacadeTool);
        assertThat(result.fallbackTools()).containsExactly(exaWebSearchTool, orderFacadeTool);
    }
}
