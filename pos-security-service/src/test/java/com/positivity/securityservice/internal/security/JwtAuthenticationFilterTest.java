package com.positivity.securityservice.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.security.service.JwtService;
import io.jsonwebtoken.MalformedJwtException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Unit tests for {@link JwtAuthenticationFilter}'s fail-closed contract (issue #1715).
 *
 * <p>The filter runs before the dispatcher and before Spring Security's
 * {@code ExceptionTranslationFilter}, so anything it throws is rendered by the servlet container
 * rather than by any {@code @ControllerAdvice} — the response would be a bare default page with
 * no {@code ApiError} envelope and no correlation id, which is exactly the hole ADR-0056 §1 and
 * ADR-0017 §3/§4 forbid. These tests pin that a token whose {@code perm_bits} claim no longer
 * decodes (and every other token-processing failure) leaves the request unauthenticated and lets
 * the chain continue, instead of escaping.
 *
 * <p>No Spring application context is needed — {@code MockHttpServletRequest} /
 * {@code MockHttpServletResponse} / {@code MockFilterChain} are used directly, matching
 * {@link GatewayHeaderAuthenticationFilterTest}.
 */
@DisplayName("JwtAuthenticationFilter")
class JwtAuthenticationFilterTest {

    private static final String TOKEN = "a-signature-valid-token";
    private static final String BEARER = "Bearer " + TOKEN;

    private JwtService jwtService;
    private UserDetailsService userDetailsService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        userDetailsService = mock(UserDetailsService.class);
        filter = new JwtAuthenticationFilter(jwtService, userDetailsService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest bearerRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/auth/subject");
        request.addHeader("Authorization", BEARER);
        return request;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("validToken_populatesContext: roles become ROLE_* authorities alongside the decoded permissions")
    void validToken_populatesContext() throws Exception {
        when(jwtService.validateToken(TOKEN)).thenReturn(true);
        when(jwtService.getUsernameFromToken(TOKEN)).thenReturn("jane.doe");
        when(jwtService.getRolesFromToken(TOKEN)).thenReturn(Set.of("SHOP_MGR"));
        when(jwtService.getAuthoritiesFromToken(TOKEN)).thenReturn(Set.of("order:shipment:cancel"));
        when(userDetailsService.loadUserByUsername("jane.doe"))
                .thenReturn(new User("jane.doe", "", List.of(new SimpleGrantedAuthority("ignored"))));

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities().stream().map(GrantedAuthority::getAuthority))
                .containsExactlyInAnyOrder("ROLE_SHOP_MGR", "order:shipment:cancel");
        assertThat(chain.getRequest()).as("chain must continue").isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fail closed: the #1715 decode failure
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "malformedPermBits_doesNotEscapeFilter: a perm_bits decode failure leaves the request unauthenticated instead of throwing out of the filter chain")
    void malformedPermBits_doesNotEscapeFilter() {
        when(jwtService.validateToken(TOKEN)).thenReturn(true);
        when(jwtService.getUsernameFromToken(TOKEN)).thenReturn("jane.doe");
        when(jwtService.getRolesFromToken(TOKEN)).thenReturn(Set.of("SHOP_MGR"));
        when(jwtService.getAuthoritiesFromToken(TOKEN))
                .thenThrow(new SecurityValidationException("Malformed Base64URL bitset: Illegal base64 character 2f"));
        when(userDetailsService.loadUserByUsername("jane.doe")).thenReturn(new User("jane.doe", "", List.of()));

        MockFilterChain chain = new MockFilterChain();

        assertThatCode(() -> filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain))
                .doesNotThrowAnyException();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest())
                .as("the chain must still run so ExceptionTranslationFilter can render the 401 envelope")
                .isNotNull();
    }

    @Test
    @DisplayName(
            "staleCatalogVersion_doesNotEscapeFilter: an unsupported perm_ver leaves the request unauthenticated instead of throwing")
    void staleCatalogVersion_doesNotEscapeFilter() {
        when(jwtService.validateToken(TOKEN)).thenReturn(true);
        when(jwtService.getUsernameFromToken(TOKEN)).thenReturn("jane.doe");
        when(jwtService.getRolesFromToken(TOKEN)).thenReturn(Set.of("SHOP_MGR"));
        when(jwtService.getAuthoritiesFromToken(TOKEN))
                .thenThrow(new SecurityValidationException("Unsupported permission catalog version: 1 (expected 2)"));
        when(userDetailsService.loadUserByUsername("jane.doe")).thenReturn(new User("jane.doe", "", List.of()));

        MockFilterChain chain = new MockFilterChain();

        assertThatCode(() -> filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain))
                .doesNotThrowAnyException();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName(
            "brokenTokenNeverInheritsGatewayAuth: a failing bearer token clears an authentication an earlier filter established")
    void brokenTokenNeverInheritsGatewayAuth() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "gateway-user", null, List.of(new SimpleGrantedAuthority("security:user:view"))));

        when(jwtService.validateToken(TOKEN)).thenReturn(true);
        when(jwtService.getUsernameFromToken(TOKEN)).thenReturn("jane.doe");
        when(jwtService.getRolesFromToken(TOKEN)).thenReturn(Set.of("SHOP_MGR"));
        when(jwtService.getAuthoritiesFromToken(TOKEN)).thenThrow(new SecurityValidationException("Malformed bitset"));
        when(userDetailsService.loadUserByUsername("jane.doe")).thenReturn(new User("jane.doe", "", List.of()));

        filter.doFilter(bearerRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("fail closed — header-derived authorities must not survive a rejected bearer token")
                .isNull();
    }

    @Test
    @DisplayName(
            "unknownSubject_doesNotEscapeFilter: a subject that no longer resolves to a user leaves the request unauthenticated")
    void unknownSubject_doesNotEscapeFilter() {
        when(jwtService.validateToken(TOKEN)).thenReturn(true);
        when(jwtService.getUsernameFromToken(TOKEN)).thenReturn("deleted.user");
        when(userDetailsService.loadUserByUsername("deleted.user"))
                .thenThrow(new UsernameNotFoundException("User not found: deleted.user"));

        MockFilterChain chain = new MockFilterChain();

        assertThatCode(() -> filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain))
                .doesNotThrowAnyException();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName(
            "claimReparseFailure_doesNotEscapeFilter: a JwtException from a post-validation claim read leaves the request unauthenticated")
    void claimReparseFailure_doesNotEscapeFilter() {
        when(jwtService.validateToken(TOKEN)).thenReturn(true);
        when(jwtService.getUsernameFromToken(TOKEN)).thenThrow(new MalformedJwtException("claims corrupt"));

        MockFilterChain chain = new MockFilterChain();

        assertThatCode(() -> filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain))
                .doesNotThrowAnyException();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Non-token requests are untouched
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("noBearerHeader_leavesContextUntouched: a request without a bearer token is passed straight through")
    void noBearerHeader_leavesContextUntouched() throws Exception {
        UsernamePasswordAuthenticationToken gatewayAuth = new UsernamePasswordAuthenticationToken(
                "gateway-user", null, List.of(new SimpleGrantedAuthority("security:user:view")));
        SecurityContextHolder.getContext().setAuthentication(gatewayAuth);

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/v1/auth/subject"), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(gatewayAuth);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("invalidToken_leavesRequestUnauthenticated: validateToken false skips authentication without throwing")
    void invalidToken_leavesRequestUnauthenticated() throws Exception {
        when(jwtService.validateToken(anyString())).thenReturn(false);

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }
}
