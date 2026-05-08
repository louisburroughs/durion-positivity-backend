package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.orchestration.agent.DomainAgentDefinition;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.service.MasterAgentRegistryLoader;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MasterAgentRegistryTest {

    @Mock
    private MasterAgentRegistryLoader loader;

    @Test
    void constructorFromLoaderPreservesSharedAndDomainRuntimeSplit() {
        Object sharedTool = new SharedToolStub();
        Object inventoryTool = new InventoryFacadeToolStub();
        when(loader.loadRegistryDefinition())
                .thenReturn(new MasterAgentRegistryLoader.LoadedMasterAgentRegistry(
                        List.of(sharedTool),
                        List.of(new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool))),
                        java.util.Map.of("ROLE_MANAGER", List.of(inventoryTool))));

        MasterAgentRegistry registry = new MasterAgentRegistry(loader);

        assertThat(registry.sharedTools()).containsExactly(sharedTool);
        assertThat(registry.domainAgents())
                .extracting(DomainAgentDefinition::agentName)
                .containsExactly("inventory");
        assertThat(registry.resolveToolsForDomainAgent("ROLE_MANAGER")).containsExactly(sharedTool, inventoryTool);
        assertThat(registry.resolveToolsForDomainAgent("inventory")).containsExactly(sharedTool, inventoryTool);
    }

    @Test
    void resolveToolsForDomainAgentReturnsMutableCopy() {
        Object domainTool = new Object();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(),
                List.of(new DomainAgentDefinition("inventory", "inventory", List.of(domainTool))));

        List<Object> firstCall = registry.resolveToolsForDomainAgent("inventory");
        firstCall.add(new Object());

        List<Object> secondCall = registry.resolveToolsForDomainAgent("inventory");

        assertThat(secondCall).hasSize(1);
        assertSame(domainTool, secondCall.getFirst());
    }

    @Test
    void resolveToolsForDomainAgentWithSelectedNamesFiltersMatchingTools() {
        Object sharedTool = new SharedToolStub();
        Object domainTool = new InventoryFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(sharedTool),
                List.of(new DomainAgentDefinition("inventory", "inventory", List.of(domainTool))));

        List<Object> tools = registry.resolveToolsForDomainAgent("inventory", List.of("inventoryFacadeToolStub"));

        assertThat(tools).containsExactly(domainTool);
    }

    @Test
    void preloadableDomainAgentsReturnsAgentNames() {
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(),
                List.of(
                        new DomainAgentDefinition("inventory", "inventory", List.of()),
                        new DomainAgentDefinition("orders", "orders", List.of())));

        assertThat(registry.preloadableDomainAgents()).containsExactly("inventory", "orders");
    }

    @Test
    void resolveToolsForDomainAgentPrefersRoleAssignmentsWhenPresent() {
        Object sharedTool = new SharedToolStub();
        Object inventoryTool = new InventoryFacadeToolStub();
        Object orderTool = new OrderFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(sharedTool),
                List.of(
                        new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool)),
                        new DomainAgentDefinition("orders", "orders", List.of(orderTool))),
                java.util.Map.of("ROLE_MANAGER", List.of(inventoryTool, orderTool)));

        assertThat(registry.resolveToolsForDomainAgent("ROLE_MANAGER"))
                .containsExactly(sharedTool, inventoryTool, orderTool);
        assertThat(registry.resolveToolsForDomainAgent("orders")).containsExactly(sharedTool, orderTool);
    }

    private static final class SharedToolStub {}

    private static final class InventoryFacadeToolStub {}

    private static final class OrderFacadeToolStub {}
}
