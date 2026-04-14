package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

/**
 * Unit tests for {@link ToolRegistryLoader} Phase 2 DB-backed behaviour.
 */
@ExtendWith(MockitoExtension.class)
class ToolRegistryLoaderTest {

    @Mock
    private ToolMetadataRepository repository;

    @Mock
    private ApplicationContext applicationContext;

    private static final ToolMetadata SAMPLE_META = new ToolMetadata(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "customerFacadeTool",
            "Customer Lookup",
            "Look up customer records",
            "customer",
            0.8,
            "low",
            50,
            true,
            "customerFacadeTool");

    @Test
    @DisplayName("loadRoleToolMappings resolves bean by handlerBean name for an enabled tool")
    void loadRoleToolMappings_withEnabledTool_resolvesBean() {
        Object fakeTool = new Object();
        // default: all roles return empty list
        when(repository.findEnabledByRoleAndWorkflow(anyString(), anyString())).thenReturn(List.of());
        // override ROLE_CASHIER to return the sample tool (last stub wins for this
        // matcher)
        when(repository.findEnabledByRoleAndWorkflow("ROLE_CASHIER", "IDLE")).thenReturn(List.of(SAMPLE_META));
        when(applicationContext.getBean("customerFacadeTool")).thenReturn(fakeTool);

        ToolRegistryLoader loader = new ToolRegistryLoader(repository, applicationContext);
        Map<String, List<Object>> result = loader.loadRoleToolMappings();

        assertThat(result.get("ROLE_CASHIER")).containsExactly(fakeTool);
    }

    @Test
    @DisplayName("loadRoleToolMappings returns empty lists for all known roles when no tools enabled")
    void loadRoleToolMappings_withNoEnabledTools_returnsEmptyListsForAllRoles() {
        when(repository.findEnabledByRoleAndWorkflow(anyString(), anyString())).thenReturn(List.of());

        ToolRegistryLoader loader = new ToolRegistryLoader(repository, applicationContext);
        Map<String, List<Object>> result = loader.loadRoleToolMappings();

        assertThat(result).isNotEmpty();
        result.values().forEach(tools -> assertThat(tools).isEmpty());
    }

    @Test
    @DisplayName("loadRoleToolMappings skips tools whose handler bean is missing from ApplicationContext")
    void loadRoleToolMappings_withMissingBean_skipsSilently() {
        when(repository.findEnabledByRoleAndWorkflow(anyString(), anyString())).thenReturn(List.of());
        when(repository.findEnabledByRoleAndWorkflow("ROLE_CASHIER", "IDLE")).thenReturn(List.of(SAMPLE_META));
        when(applicationContext.getBean("customerFacadeTool"))
                .thenThrow(new NoSuchBeanDefinitionException("customerFacadeTool"));

        ToolRegistryLoader loader = new ToolRegistryLoader(repository, applicationContext);
        Map<String, List<Object>> result = loader.loadRoleToolMappings();

        assertThat(result.get("ROLE_CASHIER")).isEmpty();
    }
}
