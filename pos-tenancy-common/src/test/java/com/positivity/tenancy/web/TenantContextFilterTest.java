package com.positivity.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantHeaders;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TenantContextFilterTest {

    private static final UUID A = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID DEFAULT = UUID.fromString("01900000-0000-7000-8000-000000000009");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC);

    private final AtomicReference<UUID> seen = new AtomicReference<>();
    private final FilterChain chain =
            (request, response) -> seen.set(TenantContext.current().orElse(null));

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static TenantContextFilter filter(TenancyProperties properties) {
        return new TenantContextFilter(properties, new ObjectMapper(), CLOCK);
    }

    @Test
    void bindsTheHeaderTenantForTheRequestAndClearsAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/locations");
        request.addHeader(TenantHeaders.HTTP_TENANT_ID, A.toString());

        filter(new TenancyProperties()).doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isEqualTo(A);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void strictModeRefusesARequestWithoutATenant() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(new TenancyProperties()).doFilter(new MockHttpServletRequest("GET", "/v1/locations"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
        assertThat(response.getContentAsString())
                .contains("\"code\":\"" + TenantContextFilter.ERROR_CODE + "\"")
                .contains("\"status\":401")
                .contains("2026-09-10T00:00:00Z");
        assertThat(seen.get()).as("the handler never ran").isNull();
    }

    @Test
    void malformedHeaderIsRefusedEvenWithADefault() throws Exception {
        TenancyProperties properties = new TenancyProperties();
        properties.setDefaultTenantId(DEFAULT);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/locations");
        request.addHeader(TenantHeaders.HTTP_TENANT_ID, "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(properties).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(seen.get()).isNull();
    }

    @Test
    void transitionalDefaultBindsWhenTheHeaderIsAbsent() throws Exception {
        TenancyProperties properties = new TenancyProperties();
        properties.setDefaultTenantId(DEFAULT);

        filter(properties)
                .doFilter(new MockHttpServletRequest("GET", "/v1/locations"), new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isEqualTo(DEFAULT);
    }

    @Test
    void infrastructurePathsAndUnenforcedModulesProceedUnbound() throws Exception {
        MockHttpServletResponse health = new MockHttpServletResponse();
        filter(new TenancyProperties()).doFilter(new MockHttpServletRequest("GET", "/actuator/health"), health, chain);
        assertThat(health.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isNull();

        TenancyProperties lenient = new TenancyProperties();
        lenient.setEnforce(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(lenient).doFilter(new MockHttpServletRequest("GET", "/v1/locations"), response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isNull();
    }

    @Test
    void correlationIdIsEchoedOnRefusal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/locations");
        request.addHeader("X-Correlation-Id", "corr-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(new TenancyProperties()).doFilter(request, response, chain);

        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("corr-1");
        assertThat(response.getContentAsString()).contains("\"correlationId\":\"corr-1\"");
    }
}
