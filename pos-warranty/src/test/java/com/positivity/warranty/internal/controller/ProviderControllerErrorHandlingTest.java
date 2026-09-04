package com.positivity.warranty.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.security.common.GatewayAuthoritiesFilter;
import com.positivity.warranty.internal.config.SecurityConfig;
import com.positivity.warranty.internal.exception.WarrantyValidationException;
import com.positivity.warranty.internal.security.WarrantyPermissions;
import com.positivity.warranty.internal.service.ProviderService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end proof for issue #1694, exercised through {@link ProviderController} to avoid a
 * request body (no JSON deserialization concerns to muddy the assertion): {@link
 * WarrantyValidationException} keeps the genuine-client-error contract (400 {@code
 * VALIDATION_ERROR}, message echoed), while a bare {@code IllegalArgumentException} — what
 * Hibernate/JPA throw for an invalid query, what {@code UUID.fromString} throws on malformed
 * stored data — is no longer caught by {@link WarrantyExceptionHandler}: it falls through to
 * {@code pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler}, which answers a
 * generic, correlated 500 that never echoes the exception's own text.
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s {@code
 * @AutoConfiguration} (it is not on the curated slice-test allowlist — an unrelated {@code
 * @AutoConfiguration} from another artifact is simply not imported by the slice), so {@link
 * WebCommonErrorAutoConfiguration} is imported explicitly here to exercise the real fallback
 * chain rather than asserting a weaker substitute.
 */
@WebMvcTest(ProviderController.class)
@Import({
    SecurityConfig.class,
    WebCommonErrorAutoConfiguration.class,
    ProviderControllerErrorHandlingTest.SliceTestConfig.class
})
class ProviderControllerErrorHandlingTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProviderService providerService;

    /** Authenticates the request the way the API gateway does: X-Authorities / X-User headers. */
    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header("X-User", "web-mvc-tester").header("X-Authorities", WarrantyPermissions.PROVIDER_VIEW);
    }

    @Test
    void aWarrantyValidationFailureAnswers400WithItsOwnCodeAndMessage() throws Exception {
        when(providerService.list(any(), any()))
                .thenThrow(new WarrantyValidationException("status 'BOGUS' is invalid"));

        mockMvc.perform(authed(get("/v1/warranty/providers").param("status", "BOGUS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("status 'BOGUS' is invalid"));
    }

    /**
     * The regression this test guards against (issue #1694): a bare {@code
     * IllegalArgumentException} must NOT come back as a 400 carrying its own message. It is an
     * unexpected server-side failure, so it must land on the generic, correlated 500 fallback.
     */
    @Test
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessageAndCarriesACorrelationId()
            throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'vendorId' of "
                + "'com.positivity.warranty.internal.entity.WarrantyProvider'";
        when(providerService.list(any(), any())).thenThrow(new IllegalArgumentException(leakCanary));

        var result = mockMvc.perform(authed(get("/v1/warranty/providers")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn();

        String correlationHeader = result.getResponse().getHeader("X-Correlation-Id");
        String body = result.getResponse().getContentAsString();

        // ADR-0017 §4: the correlation id must be present in BOTH the body and the header, and
        // they must be the same value — not just independently non-blank.
        assertThat(correlationHeader).isNotBlank();
        assertThat(body).contains("\"correlationId\":\"" + correlationHeader + "\"");
        assertThat(body)
                .doesNotContain(leakCanary)
                .doesNotContain("UnknownPathException")
                .doesNotContain("vendorId");
    }

    /** Clock for {@link WarrantyExceptionHandler} and {@code pos-web-common}'s advice, plus the gateway filter fix. */
    @TestConfiguration
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }

        /**
         * The MockMvc auto-configuration registers every {@code Filter} bean directly, which
         * would run {@code GatewayAuthoritiesFilter} (a {@code OncePerRequestFilter}) BEFORE the
         * security chain and mark it already-filtered — the in-chain instance then skips, and
         * {@code SecurityContextHolderFilter} wipes the authentication, failing every request
         * with 401. Disable the direct registration so the filter runs exactly where production
         * runs it: inside {@code gatewaySecurityFilterChain}.
         */
        @Bean
        FilterRegistrationBean<GatewayAuthoritiesFilter> gatewayAuthoritiesFilterRegistration(
                GatewayAuthoritiesFilter gatewayAuthoritiesFilter) {
            var registration = new FilterRegistrationBean<>(gatewayAuthoritiesFilter);
            registration.setEnabled(false);
            return registration;
        }
    }
}
