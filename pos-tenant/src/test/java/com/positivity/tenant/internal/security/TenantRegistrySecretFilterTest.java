package com.positivity.tenant.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

class TenantRegistrySecretFilterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);

    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static TenantRegistrySecretFilter filter(String configuredSecret) {
        return new TenantRegistrySecretFilter(configuredSecret, CLOCK, new ObjectMapper());
    }

    @Test
    @DisplayName("a blank configured secret refuses every call with TENANT_REGISTRY_SECRET_MISSING")
    void blankConfiguredSecretIsRefused() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/tenants");
        request.addHeader(TenantRegistrySecretFilter.SECRET_HEADER, "anything");
        request.addHeader("X-Correlation-Id", "corr-missing");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(" ").doFilter(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("corr-missing");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"TENANT_REGISTRY_SECRET_MISSING\"")
                .contains("\"status\":401")
                .contains("\"correlationId\":\"corr-missing\"")
                .contains("2026-09-10T12:00:00Z");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void wrongOrAbsentHeaderIsRefused() throws Exception {
        MockHttpServletRequest wrong = new MockHttpServletRequest("GET", "/internal/v1/tenants");
        wrong.addHeader(TenantRegistrySecretFilter.SECRET_HEADER, "not-it");
        MockHttpServletResponse wrongResponse = new MockHttpServletResponse();
        filter("s3cret").doFilter(wrong, wrongResponse, chain);

        MockHttpServletRequest absent = new MockHttpServletRequest("GET", "/internal/v1/tenants");
        absent.setQueryString("status=ACTIVE");
        MockHttpServletResponse absentResponse = new MockHttpServletResponse();
        filter("s3cret").doFilter(absent, absentResponse, chain);

        verifyNoInteractions(chain);
        assertThat(wrongResponse.getStatus()).isEqualTo(401);
        assertThat(wrongResponse.getContentAsString()).contains("\"code\":\"INVALID_TENANT_REGISTRY_SECRET\"");
        assertThat(wrongResponse.getHeader("X-Correlation-Id")).isNotBlank();
        assertThat(absentResponse.getStatus()).isEqualTo(401);
        assertThat(absentResponse.getContentAsString()).contains("\"code\":\"INVALID_TENANT_REGISTRY_SECRET\"");
    }

    @Test
    @DisplayName("the right secret authenticates the request as the registry client and continues")
    void matchingSecretAuthenticates() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/tenants");
        request.addHeader(TenantRegistrySecretFilter.SECRET_HEADER, "s3cret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter("s3cret").doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo(TenantRegistrySecretFilter.PRINCIPAL);
        assertThat(authentication.getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("other paths pass through untouched, secret or not")
    void otherPathsAreNotFiltered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/platform/tenants");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(" ").doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
