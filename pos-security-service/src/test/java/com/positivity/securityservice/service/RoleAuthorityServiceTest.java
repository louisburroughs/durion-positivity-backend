package com.positivity.securityservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class RoleAuthorityServiceTest {

    private final RoleAuthorityService roleAuthorityService = new RoleAuthorityService();

    @Test
    void expandRolesToAuthorities_adminIncludesRoleAndDomainAuthorities() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("ADMIN"));

        assertThat(authorities)
                .contains("ROLE_ADMIN")
                .contains("crm:party:view")
                .contains("accounting:posting_rules:archive");
    }

    @Test
    void expandRolesToAuthorities_handlesPrefixedAndBlankRoles() {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of("ROLE_CSR", " "));

        assertThat(authorities)
                .contains("ROLE_CSR")
                .contains("crm:party:view");
    }
}