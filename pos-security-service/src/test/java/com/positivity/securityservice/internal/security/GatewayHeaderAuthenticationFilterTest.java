package com.positivity.securityservice.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.domain.PermissionBitsetCodec;
import com.positivity.securityservice.internal.enums.PermissionCode;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link GatewayHeaderAuthenticationFilter}.
 * <p>
 * No Spring application context is needed — uses MockHttpServletRequest /
 * MockHttpServletResponse / MockFilterChain directly (available via
 * spring-test).
 */
@DisplayName("GatewayHeaderAuthenticationFilter")
class GatewayHeaderAuthenticationFilterTest {

    private final GatewayHeaderAuthenticationFilter filter = new GatewayHeaderAuthenticationFilter();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path: X-Perm-Bits decodes to correct authorities
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("permBits_validPayload_decodesAuthorities: valid X-Perm-Bits + matching X-Perm-Ver populates security context with decoded permissions")
    void permBits_validPayload_decodesAuthorities() throws Exception {
        Set<PermissionCode> permissions = EnumSet.of(
                PermissionCode.ACCOUNTING__JE__VIEW,   // bit 0 → "accounting:je:view"
                PermissionCode.CATALOG__PRODUCT__VIEW  // bit 11 → "catalog:product:view"
        );
        String encoded = PermissionBitsetCodec.encode(permissions);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Perm-Bits", encoded);
        request.addHeader("X-Perm-Ver", String.valueOf(PermissionCode.CATALOG_VERSION));
        request.addHeader("X-User", "test-user");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getName()).isEqualTo("test-user");

        List<String> authorityStrings = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        assertThat(authorityStrings)
                .containsExactlyInAnyOrder("accounting:je:view", "catalog:product:view");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fail closed: X-Perm-Bits present, X-Perm-Ver missing → unauthenticated
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("permBits_missingPermVer_clearsAuth: X-Perm-Bits present but X-Perm-Ver absent clears security context (fail closed)")
    void permBits_missingPermVer_clearsAuth() throws Exception {
        String encoded = PermissionBitsetCodec.encode(Set.of(PermissionCode.ACCOUNTING__JE__VIEW));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Perm-Bits", encoded);
        // deliberately omit X-Perm-Ver
        request.addHeader("X-Authorities", "csv:authority:one,csv:authority:two");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fail closed: X-Perm-Ver present but wrong version → unauthenticated
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("permBits_wrongVersion_clearsAuth: X-Perm-Ver != CATALOG_VERSION clears security context (fail closed)")
    void permBits_wrongVersion_clearsAuth() throws Exception {
        String encoded = PermissionBitsetCodec.encode(Set.of(PermissionCode.ACCOUNTING__JE__VIEW));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Perm-Bits", encoded);
        request.addHeader("X-Perm-Ver", String.valueOf(PermissionCode.CATALOG_VERSION + 1));
        request.addHeader("X-Authorities", "fallback:authority");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSV-only path: no X-Perm-Bits header
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("permBits_absent_csvWorks: no X-Perm-Bits → X-Authorities CSV path works as before")
    void permBits_absent_csvWorks() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Authorities", "security:role:view,security:permission:view");
        request.addHeader("X-User", "csv-user");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("csv-user");

        List<String> authorityStrings = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        assertThat(authorityStrings)
                .containsExactlyInAnyOrder("security:role:view", "security:permission:view");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // No auth headers at all → security context left unauthenticated
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("noAuthHeaders_noAuthenticationSet: neither X-Perm-Bits nor X-Authorities → security context is not populated")
    void noAuthHeaders_noAuthenticationSet() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // no auth headers

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNull();
    }
}
