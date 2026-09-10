package com.positivity.tenant.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.autoconfigure.TenancyWebAutoConfiguration;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class PlatformTenantGuardTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);

    private final PlatformTenantGuard.PlatformTenantFilter filter =
            new PlatformTenantGuard.PlatformTenantFilter(new ObjectMapper(), CLOCK);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void platformTenantPasses() throws Exception {
        TenantContext.bind(PlatformTenant.ID);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/platform/tenants");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("any other tenant is refused with a 403 ApiError before the handler runs")
    void customerTenantIsRefused() throws Exception {
        TenantContext.bind(UUID.fromString("01900000-0000-7000-8000-000000000001"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/platform/tenants");
        request.addHeader("X-Correlation-Id", "corr-403");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("corr-403");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"PLATFORM_TENANT_REQUIRED\"")
                .contains("\"correlationId\":\"corr-403\"")
                .contains("2026-09-10T12:00:00Z");
    }

    @Test
    @DisplayName("nothing bound is left to TenantContextFilter's rule")
    void unboundPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void generatesACorrelationIdWhenNoneIsInbound() throws Exception {
        TenantContext.bind(UUID.randomUUID());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/v1/platform/accounts"), response, chain);

        assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void registrationSitsBetweenTheTenantFilterAndSpringSecurity() {
        FilterRegistrationBean<PlatformTenantGuard.PlatformTenantFilter> registration =
                new PlatformTenantGuard().platformTenantFilter(new ObjectMapper(), CLOCK);
        assertThat(registration.getOrder()).isEqualTo(TenancyWebAutoConfiguration.FILTER_ORDER + 1);
        assertThat(registration.getOrder()).isLessThan(-100);
        assertThat(registration.getUrlPatterns()).containsExactly("/*");
    }
}
