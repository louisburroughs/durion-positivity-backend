package com.positivity.securityservice.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.security.service.JwtService;
import io.jsonwebtoken.MalformedJwtException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
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

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-05T12:00:00Z");
    private static final String TOKEN = "a-signature-valid-token";
    private static final String BEARER = "Bearer " + TOKEN;

    private JwtService jwtService;
    private UserDetailsService userDetailsService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        userDetailsService = mock(UserDetailsService.class);
        filter = new JwtAuthenticationFilter(
                jwtService, userDetailsService, new ObjectMapper(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
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
    // Account state (#1803): a live token must not outlast the account's fitness to hold one
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Issue #1803 (1): no {@code AuthenticationProvider} runs on this path, so Spring's
     * {@code AccountStatusUserDetailsChecker} — which enforces enabled / non-locked / non-expired
     * on the credential path — was invoked by nothing here. {@code CustomUserDetailsService}
     * populates all the flags faithfully; the filter simply never read them, so disabling or
     * locking an account left every access token it already held working until expiry.
     */
    @Test
    @DisplayName(
            "disabledAccount_isRefused: a valid token for a disabled account leaves the request unauthenticated without throwing")
    void disabledAccount_isRefused() {
        stubValidTokenFor(disabledAccount());

        MockFilterChain chain = new MockFilterChain();

        assertThatCode(() -> filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain))
                .doesNotThrowAnyException();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("a disabled account's live token must stop authenticating immediately")
                .isNull();
        assertThat(chain.getRequest())
                .as("the chain must still run so the entry point renders the 401 envelope")
                .isNotNull();
    }

    @Test
    @DisplayName("lockedAccount_isRefused: a valid token for a locked account leaves the request unauthenticated")
    void lockedAccount_isRefused() {
        stubValidTokenFor(lockedAccount());

        MockFilterChain chain = new MockFilterChain();

        assertThatCode(() -> filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain))
                .doesNotThrowAnyException();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("expiredAccount_isRefused: a valid token for an expired account leaves the request unauthenticated")
    void expiredAccount_isRefused() {
        stubValidTokenFor(expiredAccount());

        MockFilterChain chain = new MockFilterChain();

        assertThatCode(() -> filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain))
                .doesNotThrowAnyException();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    /**
     * The fourth flag. Nothing on this path checks a password, but {@code JwtController#issueInternalToken}
     * mints tokens with no password check at all, so a token minted after the credentials expired
     * would otherwise keep working. {@code AccountStatusUserDetailsChecker} throws
     * {@code CredentialsExpiredException} for it, and the filter must answer exactly as it does
     * for the other three: unauthenticated, chain continues, nothing written.
     */
    @Test
    @DisplayName(
            "credentialsExpired_isRefused: a valid token for an account whose credentials have expired leaves the request unauthenticated")
    void credentialsExpired_isRefused() throws Exception {
        stubValidTokenFor(credentialsExpiredAccount());

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        assertThatCode(() -> filter.doFilter(bearerRequest(), response, chain)).doesNotThrowAnyException();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("expired credentials must stop a live token authenticating, like the other three flags")
                .isNull();
        assertThat(chain.getRequest())
                .as("the chain must still run so the entry point renders the 401 envelope")
                .isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName(
            "disabledAccount_clearsGatewayAuth: a disabled account's token never rides on gateway-header authorities")
    void disabledAccount_clearsGatewayAuth() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "gateway-user", null, List.of(new SimpleGrantedAuthority("security:token:issue_internal"))));
        stubValidTokenFor(disabledAccount());

        filter.doFilter(bearerRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("fail closed — the account state refusal must deny the request, not fall back to headers")
                .isNull();
    }

    /**
     * The status check is a credential refusal, so it must answer as one: no enveloped 500, and
     * no response written by the filter — the entry point renders the 401 later in the chain.
     */
    @Test
    @DisplayName("disabledAccount_isNotAServerFault: the refusal writes nothing and is not answered as a 500")
    void disabledAccount_isNotAServerFault() throws Exception {
        stubValidTokenFor(disabledAccount());

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(bearerRequest(), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("healthyAccount_stillAuthenticates: all account-state flags true is the happy path unchanged")
    void healthyAccount_stillAuthenticates() throws Exception {
        stubValidTokenFor(healthyAccount());

        filter.doFilter(bearerRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    private void stubValidTokenFor(User account) {
        when(jwtService.validateToken(TOKEN)).thenReturn(true);
        when(jwtService.getUsernameFromToken(TOKEN)).thenReturn(account.getUsername());
        when(jwtService.getRolesFromToken(TOKEN)).thenReturn(Set.of("SHOP_MGR"));
        when(jwtService.getAuthoritiesFromToken(TOKEN)).thenReturn(Set.of("order:shipment:cancel"));
        when(userDetailsService.loadUserByUsername(account.getUsername())).thenReturn(account);
    }

    // Named factories over Spring's User(username, password, enabled, accountNonExpired,
    // credentialsNonExpired, accountNonLocked, authorities) constructor: each flips exactly one
    // flag, so a test reads as the account state it exercises rather than as a positional triple.

    private static User healthyAccount() {
        return account(true, true, true, true);
    }

    private static User disabledAccount() {
        return account(false, true, true, true);
    }

    private static User expiredAccount() {
        return account(true, false, true, true);
    }

    private static User credentialsExpiredAccount() {
        return account(true, true, false, true);
    }

    private static User lockedAccount() {
        return account(true, true, true, false);
    }

    private static User account(
            boolean enabled, boolean accountNonExpired, boolean credentialsNonExpired, boolean accountNonLocked) {
        return new User(
                "jane.doe",
                "",
                enabled,
                accountNonExpired,
                credentialsNonExpired,
                accountNonLocked,
                List.of(new SimpleGrantedAuthority("ignored")));
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

    /**
     * Raised by Copilot on PR #1801, and a real gap rather than a consistency nit: the gateway
     * validates only signature, issuer, audience and expiry — {@code pos-api-gateway} has no
     * revocation check at all — so a revoked or logged-out token still arrives here carrying
     * gateway-derived {@code X-Perm-Bits}. {@code JwtServiceImpl#validateToken} does consult
     * revocation and the token store and correctly refuses such a token, but the filter used to
     * return without clearing, leaving {@link GatewayHeaderAuthenticationFilter}'s header
     * authorities in place. Logout and revocation therefore did not take effect at the service
     * that owns them.
     */
    @Test
    @DisplayName(
            "refusedToken_clearsGatewayAuth: a revoked or logged-out token never rides on gateway-header authorities")
    void refusedToken_clearsGatewayAuth() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "gateway-user", null, List.of(new SimpleGrantedAuthority("security:token:issue_internal"))));
        when(jwtService.validateToken(TOKEN)).thenReturn(false);

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("a credential this service refuses must deny the request, not be silently ignored")
                .isNull();
        assertThat(chain.getRequest())
                .as("the chain must still run so the entry point can render the 401 envelope")
                .isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A server fault is not a bad credential: enveloped 500, never a container page
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The narrow catch list closes the #1715 instance but not the guarantee: {@code validateToken}
     * wraps its body in {@code catch (JwtException | IllegalArgumentException)} only
     * ({@code JwtServiceImpl:121}), so a {@code RedisConnectionFailureException} from the
     * revocation check or a {@code DataAccessException} from the token store propagates straight
     * out of the filter — the same bare-container-page symptom #1715 reported, from a different
     * cause. Answering 401 for it would be wrong too: the caller's token is fine, and telling them
     * to replace it misdirects the fix. So it must be an enveloped, correlated 500.
     */
    @Test
    @DisplayName(
            "infrastructureFailure_answersEnvelopedFiveHundred: a DataAccessException answers a correlated 500 envelope, not a container page and not a 401")
    void infrastructureFailure_answersEnvelopedFiveHundred() throws Exception {
        when(jwtService.validateToken(TOKEN))
                .thenThrow(new DataAccessResourceFailureException("Redis connection refused"));

        MockHttpServletRequest request = bearerRequest();
        request.addHeader("X-Correlation-Id", "corr-1715");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("corr-1715");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"INTERNAL_ERROR\"")
                .contains("\"correlationId\":\"corr-1715\"")
                .as("the failure text must not reach the client")
                .doesNotContain("Redis connection refused");
        assertThat(chain.getRequest())
                .as("the chain must NOT continue — the filter already wrote the response")
                .isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName(
            "userLookupFailure_answersEnvelopedFiveHundred: a DataAccessException from the user lookup is a server fault, not a 401")
    void userLookupFailure_answersEnvelopedFiveHundred() throws Exception {
        when(jwtService.validateToken(TOKEN)).thenReturn(true);
        when(jwtService.getUsernameFromToken(TOKEN)).thenReturn("jane.doe");
        when(userDetailsService.loadUserByUsername("jane.doe"))
                .thenThrow(new DataAccessResourceFailureException("connection pool exhausted"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(bearerRequest(), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("\"code\":\"INTERNAL_ERROR\"");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Correlation id: the reason and the rendered 401 must be joinable
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "rejectedToken_publishesCorrelationId: the filter publishes the correlation id the entry point renders on the 401")
    void rejectedToken_publishesCorrelationId() throws Exception {
        when(jwtService.validateToken(TOKEN)).thenReturn(true);
        when(jwtService.getUsernameFromToken(TOKEN)).thenReturn("jane.doe");
        when(jwtService.getRolesFromToken(TOKEN)).thenReturn(Set.of("SHOP_MGR"));
        when(jwtService.getAuthoritiesFromToken(TOKEN)).thenThrow(new SecurityValidationException("Malformed bitset"));
        when(userDetailsService.loadUserByUsername("jane.doe")).thenReturn(new User("jane.doe", "", List.of()));

        MockHttpServletRequest request = bearerRequest();
        request.addHeader("X-Correlation-Id", "corr-echo");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(JwtAuthenticationFilter.CORRELATION_ID_ATTRIBUTE))
                .as("JsonAuthenticationEntryPoint reads this attribute so the 401 quotes the same id")
                .isEqualTo("corr-echo");
    }

    @Test
    @DisplayName(
            "rejectedTokenWithoutHeader_generatesCorrelationId: with no inbound header the filter mints one and publishes it, so the 401 does not mint a different one")
    void rejectedTokenWithoutHeader_generatesCorrelationId() throws Exception {
        when(jwtService.validateToken(TOKEN)).thenReturn(false);

        MockHttpServletRequest request = bearerRequest();
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(JwtAuthenticationFilter.CORRELATION_ID_ATTRIBUTE))
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
    }
}
