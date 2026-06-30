package com.positivity.mcp.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gate 2B / #780 regression guard: candidate gating must be explainable solely by
 * {@code permissionCodes ∩ mcp_tool_permission ∩ workflow state}. The legacy role-gating methods
 * (which queried {@code mcp_role} / {@code mcp_tool_role}) must not reappear on the repository.
 */
class PermissionGatingInvariantTest {

    @Test
    @DisplayName("repository exposes permission gating and no legacy role-gating methods")
    void noLegacyRoleGatingMethods() {
        Set<String> methods = Arrays.stream(ToolMetadataRepository.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(methods).contains("findTopKByEmbeddingForPermissions");
        assertThat(methods).doesNotContain("findAllRoleNames", "findEnabledByRoleAndWorkflow");
    }
}
