package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.orchestration.agent.DomainAgentDefinition;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistryFactory;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.service.SystemPromptDefaults;
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
                        List.of(new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool)))));

        MasterAgentRegistry registry = new MasterAgentRegistry(registryFactory);

        assertThat(registry.sharedTools()).containsExactly(sharedTool);
        assertThat(registry.domainAgents())
                .extracting(DomainAgentDefinition::agentName)
                .containsExactly("inventory");
        assertThat(registry.resolveMasterTools()).containsExactly(sharedTool);
        assertThat(registry.resolveDomainTools("inventory")).containsExactly(inventoryTool);
        // Gate 2B / #780: role names are not domain agents and resolve nothing (no role preassignment).
        assertThat(registry.resolveDomainTools("ROLE_MANAGER")).isEmpty();
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
        // name->bean step must search the full registered set across every domain, not role-scoped.
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
    void preloadableRoleIdentifiersReturnsCanonicalRoleSet() {
        Object inventoryTool = new InventoryFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(), List.of(new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool))));

        // Gate 2A (#639): preload covers the canonical role set (MCP_ROLE_PRIORITY + ROLE_USER) so
        // ROLE_TECHNICIAN/ROLE_USER are never omitted. Gate 2B (#780): role->tool preassignment retired,
        // so there are no configured assignments to union — the canonical set is returned as-is.
        assertThat(registry.preloadableRoleIdentifiers())
                .containsExactlyInAnyOrderElementsOf(SystemPromptDefaults.PRELOADABLE_ROLE_IDENTIFIERS);
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
