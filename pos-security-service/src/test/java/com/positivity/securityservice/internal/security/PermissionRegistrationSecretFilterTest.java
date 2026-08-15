package com.positivity.securityservice.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Shared-secret gate on permission registration: only a POST to the register endpoints is
 * checked, the comparison is constant-time, and a rejection returns the standard error envelope
 * rather than falling through to the chain.
 */
@DisplayName("PermissionRegistrationSecretFilter")
class PermissionRegistrationSecretFilterTest {

    private static final String SECRET = "s3cr3t-value";
    private static final String SECRET_HEADER = "X-Permissions-Api-Secret";
    private static final String REGISTER_PATH = "/v1/permissions/register";
    private static final String ALIAS_PATH = "/v1/users/permissions/register";
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private PermissionRegistrationSecretFilter filter(String expectedSecret) {
        return new PermissionRegistrationSecretFilter(expectedSecret, Clock.fixed(NOW, ZoneOffset.UTC), objectMapper);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }

    @Test
    void authenticatesTheRegistrationServiceWhenTheSecretMatches() throws Exception {
        MockHttpServletRequest request = request("POST", REGISTER_PATH);
        request.addHeader(SECRET_HEADER, SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(SECRET).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo("permission-registration-service");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("security:permission:register");
    }

    @Test
    void guardsTheAliasPathAsWell() throws Exception {
        MockHttpServletRequest request = request("POST", ALIAS_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(SECRET).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void rejectsAMissingOrWrongSecretWithTheErrorEnvelope() throws Exception {
        MockHttpServletRequest request = request("POST", REGISTER_PATH);
        request.addHeader(SECRET_HEADER, "not-the-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(SECRET).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).contains("INVALID_PERMISSION_REGISTRATION_SECRET");
        assertThat(response.getContentAsString()).contains(NOW.toString());
        verify(chain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void rejectsTheCallWhenNoSecretIsConfiguredAtAll() throws Exception {
        MockHttpServletRequest request = request("POST", REGISTER_PATH);
        request.addHeader(SECRET_HEADER, SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter("  ").doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentAsString()).contains("PERMISSION_REGISTRATION_SECRET_MISSING");
    }

    @Test
    void keepsACallerSuppliedCorrelationIdOnTheRejection() throws Exception {
        MockHttpServletRequest request = request("POST", REGISTER_PATH);
        request.addHeader("X-Correlation-Id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(SECRET).doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getContentAsString()).contains("abc-123");
    }

    @Test
    void mintsACorrelationIdWhenTheCallerSendsABlankOne() throws Exception {
        MockHttpServletRequest request = request("POST", REGISTER_PATH);
        request.addHeader("X-Correlation-Id", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(SECRET).doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getContentAsString()).contains("correlationId");
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void letsAnyOtherPathThroughUnchecked() throws Exception {
        MockHttpServletRequest request = request("POST", "/v1/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(SECRET).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void letsSafeMethodsOnTheGuardedPathThrough() throws Exception {
        for (String method : new String[] {"GET", "HEAD", "OPTIONS"}) {
            MockHttpServletRequest request = request(method, REGISTER_PATH);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter(SECRET).doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }
    }

    @Test
    void letsANonPostWriteMethodThroughToTheNormalSecurityChain() throws Exception {
        MockHttpServletRequest request = request("DELETE", REGISTER_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(SECRET).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }
}
