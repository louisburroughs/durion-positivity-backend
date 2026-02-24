package com.positivity.securityservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

import com.positivity.securityservice.internal.service.RoleAuthorityServiceImpl;

class RoleAuthorityServiceTest {

    private final RoleAuthorityServiceImpl roleAuthorityService = new RoleAuthorityServiceImpl();

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