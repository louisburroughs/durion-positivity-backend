package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.orchestration.agent.DomainAgentDefinition;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

@ExtendWith(MockitoExtension.class)
class MasterAgentRegistryLoaderTest {

    @Mock
    private ToolMetadataRepository repository;

    @Mock
    private ApplicationContext applicationContext;

    @Test
    void loadRegistryDefinitionGroupsWorkflowToolsIntoMasterAndDomainRegistries() {
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
        assertThat(loaded.domainAgents())
                .extracting(DomainAgentDefinition::agentName)
                .containsExactly("inventory", "order");
        assertThat(loaded.domainAgents().getFirst().tools()).containsExactly(inventoryBean, inventoryLookupBean);
        assertThat(loaded.domainAgents().get(1).tools()).containsExactly(orderBean);
    }

    private static ToolMetadata tool(String name, String domain, String handlerBean) {
        return new ToolMetadata(
                UUID.randomUUID(), name, name, name + " description", domain, 1.0, "low", 50, true, handlerBean);
    }
}
