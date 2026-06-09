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
import com.positivity.security.common.DownstreamPermissionCatalog;

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
        request.addHeader(GatewaySecurityConstants.HEADER_AUTHORITIES, "ROLE_USER,PERM_mcp:chat:execute");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo("alice");

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

    @Test
    @DisplayName("X-Perm-Bits is decoded to PERM_ and plain authorities when version matches")
    void permBitsHeader_decodesAndExpands() throws ServletException, IOException {
        java.util.BitSet bits = new java.util.BitSet();
        bits.set(27); // PERM_crm:party:view
        bits.set(28); // PERM_crm:party:search
        String encoded = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bits.toByteArray());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/customers");
        request.addHeader(GatewaySecurityConstants.HEADER_USER, "alice");
        request.addHeader(GatewaySecurityConstants.HEADER_PERM_BITS, encoded);
        request.addHeader(GatewaySecurityConstants.HEADER_PERM_VER,
                String.valueOf(DownstreamPermissionCatalog.CATALOG_VERSION));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        Set<String> authorities = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        assertThat(authorities)
                .contains("PERM_crm:party:view")
                .contains("crm:party:view")
                .contains("PERM_crm:party:search")
                .contains("crm:party:search");
    }

    @Test
    @DisplayName("X-Perm-Bits merges X-Roles into the authority set")
    void permBitsAndRolesHeader_bothGranted() throws ServletException, IOException {
        java.util.BitSet bits = new java.util.BitSet();
        bits.set(27); // PERM_crm:party:view
        String encoded = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bits.toByteArray());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/customers");
        request.addHeader(GatewaySecurityConstants.HEADER_USER, "alice");
        request.addHeader(GatewaySecurityConstants.HEADER_PERM_BITS, encoded);
        request.addHeader(GatewaySecurityConstants.HEADER_PERM_VER,
                String.valueOf(DownstreamPermissionCatalog.CATALOG_VERSION));
        request.addHeader(GatewaySecurityConstants.HEADER_ROLES, "ROLE_ADMIN");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Set<String> authorities = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        assertThat(authorities)
                .contains("PERM_crm:party:view")
                .contains("crm:party:view")
                .contains("ROLE_ADMIN");
    }

    @Test
    @DisplayName("X-Authorities CSV still works when X-Perm-Bits is absent (legacy fallback)")
    void legacyXAuthoritiesCsv_worksWhenPermBitsAbsent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/customers");
        request.addHeader(GatewaySecurityConstants.HEADER_USER, "alice");
        request.addHeader(GatewaySecurityConstants.HEADER_AUTHORITIES, "PERM_crm:party:view,PERM_crm:party:search");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Set<String> authorities = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        assertThat(authorities)
                .contains("PERM_crm:party:view")
                .contains("crm:party:view")
                .contains("PERM_crm:party:search")
                .contains("crm:party:search");
    }

    @Test
    @DisplayName("X-Perm-Bits with wrong catalog version results in empty authorities")
    void permBitsWithWrongVersion_emptyAuthorities() throws ServletException, IOException {
        java.util.BitSet bits = new java.util.BitSet();
        bits.set(27);
        String encoded = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bits.toByteArray());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/customers");
        request.addHeader(GatewaySecurityConstants.HEADER_USER, "alice");
        request.addHeader(GatewaySecurityConstants.HEADER_PERM_BITS, encoded);
        request.addHeader(GatewaySecurityConstants.HEADER_PERM_VER, "999");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities()).isEmpty();
    }
}
