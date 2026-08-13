package com.positivity.security.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Tests for the userId half of {@link GatewayAuthoritiesFilter}.
 *
 * <p>
 * The existing {@code GatewayAuthoritiesFilterTest} covers authority decoding;
 * none of it touches the JWT payload, which is where the audit identity comes
 * from. Downstream services write {@code createdBy} and audit rows from the
 * {@code userId} this filter puts into the authentication details, so the parse
 * has one job: produce a UUID that really came from the token, or produce
 * nothing.
 *
 * <p>
 * Two things make that worth its own tests. The filter reads the payload without
 * verifying the signature — it can, because the gateway has already validated
 * the token and nothing but the gateway is supposed to reach these ports
 * (ADR-0011/0014) — so every malformed input has to be handled defensively here
 * rather than being caught by a library. And the failure policy is asymmetric:
 * an unreadable token drops the userId but <em>keeps</em> the authorities, so the
 * request still runs. That is deliberate, and it means a regression in this
 * parse does not fail a request, it silently produces audit rows with no author.
 */
@DisplayName("GatewayAuthoritiesFilter — resolving the audit userId from the JWT")
class GatewayAuthoritiesFilterUserIdTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final GatewayAuthoritiesFilter filter = new GatewayAuthoritiesFilter();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Builds an unsigned three-part token whose payload is the given JSON. */
    private static String tokenWithPayload(String payloadJson) {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url(payloadJson);
        return header + "." + payload + ".signature-not-checked-here";
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Authentication runFilter(String authorizationHeader) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/crm/parties");
        request.addHeader(GatewaySecurityConstants.HEADER_USER, "jsmith");
        request.addHeader(GatewaySecurityConstants.HEADER_AUTHORITIES, "crm:party:view");
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader);
        }
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static Object userIdFrom(Authentication authentication) {
        assertThat(authentication).isNotNull();
        assertThat(authentication.getDetails()).isInstanceOf(Map.class);
        return ((Map<?, ?>) authentication.getDetails()).get(GatewaySecurityConstants.DETAIL_USER_ID);
    }

    @Test
    @DisplayName("the uid claim becomes the audit userId")
    void uidClaimIsResolved() throws Exception {
        Authentication authentication = runFilter("Bearer "
                + tokenWithPayload(
                        "{\"" + GatewaySecurityConstants.CLAIM_UID + "\":\"" + USER_ID + "\",\"sub\":\"jsmith\"}"));

        assertThat(userIdFrom(authentication)).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("the legacy userId claim is still honoured")
    void legacyClaimIsResolved() throws Exception {
        Authentication authentication = runFilter("Bearer "
                + tokenWithPayload("{\"" + GatewaySecurityConstants.CLAIM_USER_ID_LEGACY + "\":\"" + USER_ID + "\"}"));

        // Tokens issued before the claim was renamed are still in circulation until they
        // expire, so dropping this fallback would blank the audit identity for every user
        // holding one.
        assertThat(userIdFrom(authentication)).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("uid wins when both claims are present")
    void uidWinsOverLegacy() throws Exception {
        UUID legacyId = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        Authentication authentication = runFilter("Bearer "
                + tokenWithPayload("{\"" + GatewaySecurityConstants.CLAIM_UID + "\":\"" + USER_ID + "\",\""
                        + GatewaySecurityConstants.CLAIM_USER_ID_LEGACY + "\":\"" + legacyId + "\"}"));

        assertThat(userIdFrom(authentication)).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("a blank uid falls through to the legacy claim rather than winning as empty")
    void blankUidFallsThroughToLegacy() throws Exception {
        Authentication authentication = runFilter("Bearer "
                + tokenWithPayload("{\"" + GatewaySecurityConstants.CLAIM_UID + "\":\"   \",\""
                        + GatewaySecurityConstants.CLAIM_USER_ID_LEGACY + "\":\"" + USER_ID + "\"}"));

        // The claim search is "first non-blank", not "first present". A present-but-empty uid
        // must not shadow a usable legacy claim.
        assertThat(userIdFrom(authentication)).isEqualTo(USER_ID);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            value = {
                "no Authorization header at all | NULL",
                "a header that is not a bearer token | Basic dXNlcjpwYXNz",
                "a bearer prefix with nothing after it | Bearer ",
                "a bearer token that is only whitespace | Bearer    ",
                "a token with fewer than two dot-separated parts | Bearer not-a-jwt",
                "a token whose payload is not valid base64 | Bearer header.!!!not-base64!!!.sig"
            },
            delimiter = '|',
            nullValues = "NULL")
    @DisplayName("an unusable token leaves the userId absent without failing the request")
    void unusableTokensLeaveUserIdAbsent(String description, String authorizationHeader) throws Exception {
        Authentication authentication = runFilter(authorizationHeader);

        // This is the asymmetry worth pinning. The request is still authenticated and still
        // carries its authorities — it will do its work — but there is no userId to attribute
        // that work to. It fails open on identity and closed on nothing, which is the opposite
        // of what the authority path does, so it must stay a deliberate choice rather than an
        // accident of a catch block.
        assertThat(authentication).as(description).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting(a -> a.getAuthority())
                .contains("crm:party:view");
        assertThat(userIdFrom(authentication)).as(description).isNull();
    }

    @ParameterizedTest(name = "payload {0} leaves the userId absent")
    @ValueSource(
            strings = {
                "{}",
                "{\"sub\":\"jsmith\"}",
                "{\"uid\":\"\"}",
                "{\"uid\":\"   \"}",
                "{\"uid\":\"not-a-uuid\"}",
                "{\"uid\":12345}"
            })
    @DisplayName("a payload with no usable identity claim leaves the userId absent")
    void unusablePayloadsLeaveUserIdAbsent(String payloadJson) throws Exception {
        Authentication authentication = runFilter("Bearer " + tokenWithPayload(payloadJson));

        // "not-a-uuid" and the numeric case are the ones that reach UUID.fromString and throw;
        // they must be caught here rather than propagating out of a security filter, where the
        // failure would be a 500 on every request from a user with a slightly odd token.
        assertThat(userIdFrom(authentication)).isNull();
    }

    @Test
    @DisplayName("a payload that is not JSON at all is handled")
    void nonJsonPayloadIsHandled() throws Exception {
        Authentication authentication = runFilter("Bearer " + tokenWithPayload("this is not json"));

        assertThat(authentication).isNotNull();
        assertThat(userIdFrom(authentication)).isNull();
    }

    @Test
    @DisplayName("the username in the details comes from X-User")
    void usernameComesFromHeader() throws Exception {
        Authentication authentication = runFilter("Bearer " + tokenWithPayload("{\"uid\":\"" + USER_ID + "\"}"));

        assertThat(((Map<?, ?>) authentication.getDetails()).get(GatewaySecurityConstants.DETAIL_USERNAME))
                .isEqualTo("jsmith");
    }

    @Test
    @DisplayName("a request with no X-User falls back to the anonymous name but still authenticates")
    void missingUserHeaderFallsBackToAnonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/crm/parties");
        request.addHeader(GatewaySecurityConstants.HEADER_AUTHORITIES, "crm:party:view");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // Worth knowing rather than approving of: authorities without a user still produce an
        // authenticated context, named "anonymous". SecurityContextHelper rejects that
        // principal outright, so anything needing an identity fails loudly downstream.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo(GatewaySecurityConstants.ANONYMOUS_USER);
    }

    @Test
    @DisplayName("actuator paths bypass the filter entirely")
    void actuatorIsBypassed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.addHeader(GatewaySecurityConstants.HEADER_USER, "jsmith");
        request.addHeader(GatewaySecurityConstants.HEADER_AUTHORITIES, "crm:party:view");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // Probes must work before and independently of any gateway wiring, so the filter
        // returns early. That also means headers on an actuator path are ignored rather than
        // trusted — a probe cannot be used to smuggle authorities in.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("a request with no gateway headers clears any inherited context")
    void noHeadersClearsContext() throws Exception {
        var stale = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "left-over-user", null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(stale);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/crm/parties");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // Threads are pooled. An authentication left behind by a previous request on the same
        // thread must be cleared, not inherited — otherwise an unauthenticated request runs as
        // whoever used the thread last.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
