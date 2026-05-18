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
    void preloadableDomainAgentsReturnsAgentNames() {
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(),
                List.of(
                        new DomainAgentDefinition("inventory", "inventory", List.of()),
                        new DomainAgentDefinition("orders", "orders", List.of())));

        assertThat(registry.preloadableDomainAgents()).containsExactly("inventory", "orders");
    }

    @Test
    void preloadableRoleIdentifiersPreferRoleAssignments() {
        Object inventoryTool = new InventoryFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(),
                List.of(new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool))),
                java.util.Map.of(
                        "ROLE_MANAGER", List.of(inventoryTool),
                        "ROLE_CASHIER", List.of(inventoryTool)));

        assertThat(registry.preloadableRoleIdentifiers()).containsExactly("ROLE_CASHIER", "ROLE_MANAGER");
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
    void resolveDomainToolsNormalizesSecurityRolesToRegistryRoles() {
        Object inventoryTool = new InventoryFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(),
                List.of(new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool))),
                java.util.Map.of("ROLE_SERVICE_WRITER", List.of(inventoryTool)));

        assertThat(registry.resolveDomainTools("ROLE_SERVICE_ADVISOR")).containsExactly(inventoryTool);
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
