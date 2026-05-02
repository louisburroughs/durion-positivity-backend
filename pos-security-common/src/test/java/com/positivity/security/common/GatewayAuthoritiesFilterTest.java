package com.positivity.security.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class GatewayAuthoritiesFilterTest {

    private final GatewayAuthoritiesFilter filter = new GatewayAuthoritiesFilter();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("gateway PERM authorities also grant plain permission strings for downstream @PreAuthorize checks")
    void permPrefixedAuthorityAlsoAddsPlainPermissionAuthority() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/mcp/chat");
        request.addHeader(GatewaySecurityConstants.HEADER_USER, "alice");
        request.addHeader(
                GatewaySecurityConstants.HEADER_AUTHORITIES,
                "ROLE_USER,PERM_mcp:chat:execute");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("alice");

        Set<String> authorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(grantedAuthority -> grantedAuthority.getAuthority())
                .collect(Collectors.toSet());

        assertThat(authorities)
                .contains("ROLE_USER")
                .contains("PERM_mcp:chat:execute")
                .contains("mcp:chat:execute");
    }

    @Test
    @DisplayName("gateway X-Roles header is merged into downstream granted authorities")
    void rolesHeaderAddsRoleAuthorities() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/mcp/chat");
        request.addHeader(GatewaySecurityConstants.HEADER_USER, "alice");
        request.addHeader(GatewaySecurityConstants.HEADER_AUTHORITIES, "PERM_mcp:chat:execute");
        request.addHeader(GatewaySecurityConstants.HEADER_ROLES, "ROLE_ADMIN");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Set<String> authorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(grantedAuthority -> grantedAuthority.getAuthority())
                .collect(Collectors.toSet());

        assertThat(authorities)
                .contains("ROLE_ADMIN")
                .contains("PERM_mcp:chat:execute")
                .contains("mcp:chat:execute");
    }
}
