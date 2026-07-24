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
 * Gate 2B / #780: the loader no longer consults mcp_role / mcp_tool_role, and role->tool
 * preassignment is removed; tool visibility is determined by permission gating at request time.
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
    }

    @Test
    void toolWithNullHandlerBeanIsSkippedNotCrashing() {
        // Regression: openapi-discovered rows have no handler_bean. If one reaches the loader it must
        // be skipped, not passed to getBean(null) (which throws "'name' must not be null" and crashes
        // context init — the mcp-server crash-loop on 2026-07-01).
        ToolMetadata openapiTool = tool("event-receiver_getactiveeventtypes", "event-receiver", null);
        when(repository.findEnabledByWorkflow("IDLE")).thenReturn(List.of(openapiTool));

        MasterAgentRegistryLoader loader = new MasterAgentRegistryLoader(repository, applicationContext, "idle");
        MasterAgentRegistryLoader.LoadedMasterAgentRegistry loaded = loader.loadRegistryDefinition();

        assertThat(loaded.sharedTools()).isEmpty();
        assertThat(loaded.domainToolAssignments()).isEmpty();
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
    }

    @Test
    void preloadsUnionOfToolsAcrossConfiguredWorkflowStates() {
        // #778: a session in a non-IDLE state must find its gated tool beans in the registry, so the
        // loader preloads the union across the configured states (not just IDLE).
        ToolMetadata idleTool = tool("OrderFacadeTool", "order", "orderFacadeTool");
        ToolMetadata poTool = tool("InventoryFacadeTool", "inventory", "inventoryFacadeTool");
        Object orderBean = new Object();
        Object inventoryBean = new Object();
        when(repository.findEnabledByWorkflow("IDLE")).thenReturn(List.of(idleTool));
        when(repository.findEnabledByWorkflow("CREATING_PO")).thenReturn(List.of(poTool));
        when(applicationContext.getBean("orderFacadeTool")).thenReturn(orderBean);
        when(applicationContext.getBean("inventoryFacadeTool")).thenReturn(inventoryBean);

        MasterAgentRegistryLoader loader =
                new MasterAgentRegistryLoader(repository, applicationContext, "IDLE,CREATING_PO");
        MasterAgentRegistryLoader.LoadedMasterAgentRegistry loaded = loader.loadRegistryDefinition();

        assertThat(loaded.domainToolAssignments())
                .containsEntry("order", List.of(orderBean))
                .containsEntry("inventory", List.of(inventoryBean));
    }

    private static ToolMetadata tool(String name, String domain, String handlerBean) {
        return new ToolMetadata(
                UUID.randomUUID(), name, name, name + " description", domain, 1.0, "low", 50, true, handlerBean);
    }
}
