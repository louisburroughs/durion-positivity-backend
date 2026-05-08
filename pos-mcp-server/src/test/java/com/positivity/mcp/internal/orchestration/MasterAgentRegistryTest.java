package com.positivity.mcp.internal.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.positivity.mcp.internal.orchestration.agent.DomainAgentDefinition;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class MasterAgentRegistryTest {

    @Test
    void exposesSharedToolsSeparatelyFromDomainAgents() {
        Object exaTool = new Object();
        Object inventoryTool = new Object();
        DomainAgentDefinition inventory = new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool));

        MasterAgentRegistry registry = new MasterAgentRegistry(List.of(exaTool), List.of(inventory));

        assertEquals(List.of(exaTool), registry.sharedTools());
        assertTrue(registry.findDomainAgent("inventory").isPresent());
        assertSame(inventoryTool, registry.findDomainAgent("inventory").orElseThrow().tools().getFirst());
    }

    @Test
    void resolveToolsForRoleCombinesSharedAndDomainTools() {
        Object sharedTool = new Object();
        Object domainTool = new InventoryFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(sharedTool),
                List.of(new DomainAgentDefinition("inventory", "inventory", List.of(domainTool))));

        List<Object> tools = registry.resolveToolsForRole("inventory");

        assertIterableEquals(List.of(sharedTool, domainTool), tools);
    }

    @Test
    void resolveToolsForRoleReturnsMutableCopy() {
        Object domainTool = new Object();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(),
                List.of(new DomainAgentDefinition("inventory", "inventory", List.of(domainTool))));

        List<Object> firstCall = registry.resolveToolsForRole("inventory");
        firstCall.add(new Object());

        List<Object> secondCall = registry.resolveToolsForRole("inventory");

        assertEquals(1, secondCall.size());
        assertSame(domainTool, secondCall.getFirst());
    }

    @Test
    void resolveToolsForRoleWithSelectedNamesFiltersMatchingTools() {
        Object sharedTool = new SharedToolStub();
        Object domainTool = new InventoryFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(sharedTool),
                List.of(new DomainAgentDefinition("inventory", "inventory", List.of(domainTool))));

        List<Object> tools = registry.resolveToolsForRole("inventory", List.of("inventoryFacadeToolStub"));

        assertIterableEquals(List.of(domainTool), tools);
    }

    @Test
    void preloadableRolesReturnsDomainAgentNames() {
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(),
                List.of(
                        new DomainAgentDefinition("inventory", "inventory", List.of()),
                        new DomainAgentDefinition("orders", "orders", List.of())));

        assertIterableEquals(List.of("inventory", "orders"), registry.preloadableRoles());
    }

    private static final class SharedToolStub {}

    private static final class InventoryFacadeToolStub {}
}
