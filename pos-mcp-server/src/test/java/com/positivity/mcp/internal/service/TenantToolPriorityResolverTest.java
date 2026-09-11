package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolPriorityOverlay;
import com.positivity.mcp.internal.repository.ToolPriorityRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Overlay resolution (ADR-0062 plan WS6): the bound tenant's overlay wins tool by tool, a tool
 * without an overlay row keeps the global priority, and a tenant with no overlay gets the global set.
 */
@DisplayName("TenantToolPriorityResolver")
class TenantToolPriorityResolverTest {

    private static final UUID INVENTORY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final ToolMetadata INVENTORY_TOOL = tool(INVENTORY, "InventoryFacadeTool", 1.0, 220);
    private static final ToolMetadata ORDER_TOOL = tool(ORDER, "OrderFacadeTool", 0.9, 240);

    private ToolPriorityRepository repository;
    private TenantToolPriorityResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(ToolPriorityRepository.class);
        resolver = new TenantToolPriorityResolver(repository);
    }

    @Test
    @DisplayName("the tenant's overlay row replaces the global priority and latency of that tool")
    void overlayWins() {
        when(repository.findOverlayForCurrentTenant())
                .thenReturn(Map.of(INVENTORY, new ToolPriorityOverlay(INVENTORY, 0.35, 900)));

        List<ToolMetadata> resolved = resolver.apply(List.of(INVENTORY_TOOL));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().priority()).isEqualTo(0.35);
        assertThat(resolved.getFirst().avgLatencyMs()).isEqualTo(900);
        assertThat(resolved.getFirst())
                .as("every other field is the catalog row's")
                .usingRecursiveComparison()
                .ignoringFields("priority", "avgLatencyMs")
                .isEqualTo(INVENTORY_TOOL);
    }

    @Test
    @DisplayName("a tool without an overlay row falls back to the global priority, tool by tool")
    void fallbackPerTool() {
        when(repository.findOverlayForCurrentTenant())
                .thenReturn(Map.of(INVENTORY, new ToolPriorityOverlay(INVENTORY, 0.35, 900)));

        List<ToolMetadata> resolved = resolver.apply(List.of(INVENTORY_TOOL, ORDER_TOOL));

        assertThat(resolved).extracting(ToolMetadata::name).containsExactly("InventoryFacadeTool", "OrderFacadeTool");
        assertThat(resolved.get(0).priority()).isEqualTo(0.35);
        assertThat(resolved.get(1)).as("no overlay row: the global row as is").isSameAs(ORDER_TOOL);
    }

    @Test
    @DisplayName("a tenant with no overlay at all gets the global set unchanged")
    void fallbackToGlobalSet() {
        when(repository.findOverlayForCurrentTenant()).thenReturn(Map.of());

        List<ToolMetadata> tools = List.of(INVENTORY_TOOL, ORDER_TOOL);

        assertThat(resolver.apply(tools)).isSameAs(tools);
        assertThat(resolver.currentOverlay().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a tenant with no invocation history reads as an empty overlay: nothing to apply, nothing lost")
    void tenantWithNoHistory() {
        // The repository reads the overlay through the bound connection; a tenant that has never
        // executed a tool (or was never tuned live) has no rows there.
        when(repository.findOverlayForCurrentTenant()).thenReturn(Map.of());

        assertThat(resolver.apply(List.of())).isEmpty();
        assertThat(resolver.apply(List.of(ORDER_TOOL))).containsExactly(ORDER_TOOL);
    }

    @Test
    @DisplayName("one overlay snapshot serves every list of a resolution: the overlay is read once")
    void snapshotIsReadOnce() {
        when(repository.findOverlayForCurrentTenant())
                .thenReturn(Map.of(ORDER, new ToolPriorityOverlay(ORDER, 0.2, 50)));

        TenantToolPriorityResolver.Overlay overlay = resolver.currentOverlay();
        List<ToolMetadata> gated = overlay.apply(List.of(INVENTORY_TOOL, ORDER_TOOL));
        List<ToolMetadata> candidates = overlay.apply(List.of(ORDER_TOOL));

        verify(repository, times(1)).findOverlayForCurrentTenant();
        assertThat(gated.get(1).priority()).isEqualTo(0.2);
        assertThat(candidates.getFirst().priority()).isEqualTo(0.2);
        assertThat(gated.get(0)).isSameAs(INVENTORY_TOOL);
    }

    private static ToolMetadata tool(UUID id, String name, double priority, int latency) {
        return new ToolMetadata(id, name, name, name + " description", "domain", priority, "low", latency, true, name);
    }
}
