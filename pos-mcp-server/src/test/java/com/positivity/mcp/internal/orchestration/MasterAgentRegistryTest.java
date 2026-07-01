package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.orchestration.agent.DomainAgentDefinition;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistryFactory;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class MasterAgentRegistryTest {

    @Mock
    private MasterAgentRegistryFactory registryFactory;

    @Test
    void constructorFromLoaderPreservesSharedAndDomainRuntimeSplit() {
        Object sharedTool = new SharedToolStub();
        Object inventoryTool = new InventoryFacadeToolStub();
        when(registryFactory.loadRegistryDefinition())
                .thenReturn(new MasterAgentRegistryFactory.LoadedMasterAgentRegistry(
                        List.of(sharedTool),
                        List.of(new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool))),
                        java.util.Map.of("ROLE_MANAGER", List.of(inventoryTool))));

        MasterAgentRegistry registry = new MasterAgentRegistry(registryFactory);

        assertThat(registry.sharedTools()).containsExactly(sharedTool);
        assertThat(registry.domainAgents())
                .extracting(DomainAgentDefinition::agentName)
                .containsExactly("inventory");
        assertThat(registry.resolveMasterTools()).containsExactly(sharedTool);
        assertThat(registry.resolveDomainTools("ROLE_MANAGER")).containsExactly(inventoryTool);
        assertThat(registry.resolveDomainTools("inventory")).containsExactly(inventoryTool);
    }

    @Test
    void resolveMasterToolsReturnsMutableCopy() {
        Object sharedTool = new SharedToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(sharedTool), List.of(new DomainAgentDefinition("inventory", "inventory", List.of())));

        List<Object> firstCall = registry.resolveMasterTools();
        firstCall.add(new Object());

        List<Object> secondCall = registry.resolveMasterTools();

        assertThat(secondCall).hasSize(1);
        assertSame(sharedTool, secondCall.getFirst());
    }

    @Test
    void resolveDomainToolsReturnsMutableCopy() {
        Object domainTool = new Object();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(), List.of(new DomainAgentDefinition("inventory", "inventory", List.of(domainTool))));

        List<Object> firstCall = registry.resolveDomainTools("inventory");
        firstCall.add(new Object());

        List<Object> secondCall = registry.resolveDomainTools("inventory");

        assertThat(secondCall).hasSize(1);
        assertSame(domainTool, secondCall.getFirst());
    }

    @Test
    void resolveDomainToolsWithSelectedNamesFiltersMatchingTools() {
        Object sharedTool = new SharedToolStub();
        Object domainTool = new InventoryFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(sharedTool), List.of(new DomainAgentDefinition("inventory", "inventory", List.of(domainTool))));

        List<Object> tools = registry.resolveDomainTools("inventory", List.of("inventoryFacadeToolStub"));

        assertThat(tools).containsExactly(domainTool);
    }

    @Test
    void resolveToolsByNameSearchesAllDomainsIgnoringRoleScope() {
        Object sharedTool = new SharedToolStub();
        Object inventoryTool = new InventoryFacadeToolStub();
        Object orderTool = new OrderFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(sharedTool),
                List.of(
                        new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool)),
                        new DomainAgentDefinition("orders", "orders", List.of(orderTool))));

        // Regression (facade tool binding): permission gating + scoring run upstream, so the
        // name->bean step must search the full registered set. A role caller (ROLE_ADMIN) resolves
        // nothing via the role-scoped overload because tools are bucketed by domain, never by role.
        assertThat(registry.resolveDomainTools("ROLE_ADMIN", List.of("orderFacadeToolStub")))
                .isEmpty();
        assertThat(registry.resolveToolsByName(
                        List.of("orderFacadeToolStub", "inventoryFacadeToolStub", "sharedToolStub")))
                .containsExactlyInAnyOrder(orderTool, inventoryTool, sharedTool);
    }

    @Test
    void resolveToolsByNameReturnsEmptyForEmptySelection() {
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(new SharedToolStub()),
                List.of(new DomainAgentDefinition("inventory", "inventory", List.of(new InventoryFacadeToolStub()))));

        assertThat(registry.resolveToolsByName(List.of())).isEmpty();
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
    void preloadableRoleIdentifiersUnionCanonicalSetWithAssignments() {
        Object inventoryTool = new InventoryFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(),
                List.of(new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool))),
                java.util.Map.of(
                        "ROLE_MANAGER", List.of(inventoryTool),
                        "ROLE_CASHIER", List.of(inventoryTool)));

        // Gate 2A (#639): preload covers the canonical role set (MCP_ROLE_PRIORITY + ROLE_USER)
        // unioned with any configured assignments — so ROLE_TECHNICIAN/ROLE_USER are never omitted.
        assertThat(registry.preloadableRoleIdentifiers())
                .contains("ROLE_CASHIER", "ROLE_MANAGER", "ROLE_USER", "ROLE_TECHNICIAN", "ROLE_SERVICE_ADVISOR");
    }

    @Test
    void resolveDomainToolsPrefersRoleAssignmentsWhenPresent() {
        Object sharedTool = new SharedToolStub();
        Object inventoryTool = new InventoryFacadeToolStub();
        Object orderTool = new OrderFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(sharedTool),
                List.of(
                        new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool)),
                        new DomainAgentDefinition("orders", "orders", List.of(orderTool))),
                java.util.Map.of("ROLE_MANAGER", List.of(inventoryTool, orderTool)));

        assertThat(registry.resolveDomainTools("ROLE_MANAGER")).containsExactly(inventoryTool, orderTool);
        assertThat(registry.resolveDomainTools("orders")).containsExactly(orderTool);
    }

    @Test
    void resolveDomainToolsUsesRawRoleNameNoAliasing() {
        Object inventoryTool = new InventoryFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(),
                List.of(new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool))),
                java.util.Map.of("ROLE_SERVICE_WRITER", List.of(inventoryTool)));

        // Gate 2B (#780): ToolRegistryRoleMapper role aliasing retired — lookup is by the raw role
        // name, no ROLE_SERVICE_ADVISOR -> ROLE_SERVICE_WRITER normalization.
        assertThat(registry.resolveDomainTools("ROLE_SERVICE_WRITER")).containsExactly(inventoryTool);
        assertThat(registry.resolveDomainTools("ROLE_SERVICE_ADVISOR")).isEmpty();
    }

    @Test
    void resolveRagScopeForToolsReturnsMasterWhenSharedAndDomainToolsMixed() {
        Object sharedTool = new SharedToolStub();
        Object inventoryTool = new InventoryFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(sharedTool),
                List.of(new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool))));

        assertThat(registry.resolveRagScopeForTools(List.of(sharedTool, inventoryTool)))
                .isEqualTo("master");
    }

    @Test
    void resolveRagScopeForToolsReturnsMasterWhenUnregisteredFallbackAndDomainToolsMixed() {
        ExaWebSearchTool exaWebSearchTool =
                new ExaWebSearchTool(RestClient.builder(), "https://api.exa.ai", "", "auto", 5);
        Object inventoryTool = new InventoryFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(), List.of(new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool))));

        assertThat(registry.resolveRagScopeForTools(List.of(inventoryTool, exaWebSearchTool)))
                .isEqualTo("master");
    }

    private static final class SharedToolStub {}

    private static final class InventoryFacadeToolStub {}

    private static final class OrderFacadeToolStub {}
}
