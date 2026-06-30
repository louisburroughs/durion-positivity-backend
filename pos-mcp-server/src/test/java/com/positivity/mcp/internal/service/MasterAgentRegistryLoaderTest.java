package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

/**
 * Gate 2B / #780: the loader no longer consults mcp_role / mcp_tool_role. roleToolAssignments is
 * always empty; tool visibility is determined by permission gating at request time.
 */
@ExtendWith(MockitoExtension.class)
class MasterAgentRegistryLoaderTest {

    @Mock
    private ToolMetadataRepository repository;

    @Mock
    private ApplicationContext applicationContext;

    @Test
    void buildsCanonicalDomainAgentsFromWorkflowDomainsWithNoRoleAssignments() {
        ToolMetadata masterTool = tool("ExaWebSearchTool", "master", "exaWebSearchTool");
        ToolMetadata inventoryFacadeTool = tool("InventoryFacadeTool", "inventory", "inventoryFacadeTool");
        ToolMetadata inventoryLookupTool = tool("InventoryLookupTool", "inventory", "inventoryLookupTool");
        ToolMetadata orderTool = tool("OrderFacadeTool", "order", "orderFacadeTool");
        Object sharedBean = new Object();
        Object inventoryBean = new Object();
        Object inventoryLookupBean = new Object();
        Object orderBean = new Object();
        when(repository.findEnabledByWorkflow("IDLE"))
                .thenReturn(List.of(orderTool, inventoryFacadeTool, masterTool, inventoryLookupTool));
        when(applicationContext.getBean("exaWebSearchTool")).thenReturn(sharedBean);
        when(applicationContext.getBean("inventoryFacadeTool")).thenReturn(inventoryBean);
        when(applicationContext.getBean("inventoryLookupTool")).thenReturn(inventoryLookupBean);
        when(applicationContext.getBean("orderFacadeTool")).thenReturn(orderBean);

        MasterAgentRegistryLoader loader = new MasterAgentRegistryLoader(repository, applicationContext, "idle");

        MasterAgentRegistryLoader.LoadedMasterAgentRegistry loaded = loader.loadRegistryDefinition();

        assertThat(loaded.sharedTools()).containsExactly(sharedBean);
        assertThat(loaded.domainToolAssignments())
                .containsEntry("inventory", List.of(inventoryBean, inventoryLookupBean))
                .containsEntry("order", List.of(orderBean));
        // Legacy role-scoped preassignment retired — always empty.
        assertThat(loaded.roleToolAssignments()).isEmpty();
    }

    @Test
    void sharedOnlyWorkflowYieldsNoDomainOrRoleAssignments() {
        ToolMetadata masterTool = tool("ExaWebSearchTool", "master", "exaWebSearchTool");
        Object sharedBean = new Object();
        when(repository.findEnabledByWorkflow("IDLE")).thenReturn(List.of(masterTool));
        when(applicationContext.getBean("exaWebSearchTool")).thenReturn(sharedBean);

        MasterAgentRegistryLoader loader = new MasterAgentRegistryLoader(repository, applicationContext, "idle");

        MasterAgentRegistryLoader.LoadedMasterAgentRegistry loaded = loader.loadRegistryDefinition();

        assertThat(loaded.sharedTools()).containsExactly(sharedBean);
        assertThat(loaded.domainToolAssignments()).isEmpty();
        assertThat(loaded.roleToolAssignments()).isEmpty();
    }

    private static ToolMetadata tool(String name, String domain, String handlerBean) {
        return new ToolMetadata(
                UUID.randomUUID(), name, name, name + " description", domain, 1.0, "low", 50, true, handlerBean);
    }
}
