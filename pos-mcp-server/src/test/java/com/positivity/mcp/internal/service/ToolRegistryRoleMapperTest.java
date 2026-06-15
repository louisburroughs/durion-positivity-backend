package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolRegistryRoleMapperTest {

    @Test
    @DisplayName("normalize maps service advisor role to seeded service writer registry role")
    void normalize_mapsServiceAdvisorToServiceWriter() {
        assertThat(ToolRegistryRoleMapper.normalize("ROLE_SERVICE_ADVISOR")).isEqualTo("ROLE_SERVICE_WRITER");
    }

    @Test
    @DisplayName("normalize maps location manager role to seeded manager registry role")
    void normalize_mapsLocationManagerToManager() {
        assertThat(ToolRegistryRoleMapper.normalize("ROLE_LOCATION_MANAGER")).isEqualTo("ROLE_MANAGER");
    }

    @Test
    @DisplayName("normalize maps system administrator role to seeded admin registry role")
    void normalize_mapsSystemAdministratorToAdmin() {
        assertThat(ToolRegistryRoleMapper.normalize("ROLE_SYSTEM_ADMINISTRATOR"))
                .isEqualTo("ROLE_ADMIN");
    }

    @Test
    @DisplayName("normalize leaves seeded registry roles unchanged")
    void normalize_leavesSeededRolesUnchanged() {
        assertThat(ToolRegistryRoleMapper.normalize("ROLE_SUPPLIER")).isEqualTo("ROLE_SUPPLIER");
    }
}
