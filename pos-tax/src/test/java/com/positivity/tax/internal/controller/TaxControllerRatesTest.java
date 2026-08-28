package com.positivity.tax.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.tax.common.dto.TaxRateComponent;
import com.positivity.tax.common.dto.TaxRateLookupResponse;
import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.internal.config.SecurityConfig;
import com.positivity.tax.internal.exception.TaxRateLookupUnsupportedException;
import com.positivity.tax.internal.service.TaxCalculationService;
import com.positivity.tax.internal.service.TaxProviderLifecycleService;
import com.positivity.tax.internal.service.TaxRateLookupService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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
 * Web-layer contract of {@link TaxController#getRates}, the jurisdiction rate lookup endpoint
 * (issue #1522): 200 happy path, 400 on invalid address parameters, 501 when the active
 * provider does not support rate-only lookup, and the {@code tax:rates:view} permission gate —
 * exercised via the imported production {@link SecurityConfig}/{@code GatewaySecurityConfig}
 * chain, mirroring {@code WarrantyControllersWebMvcTest}. The other TaxController endpoints
 * ({@code /calculate}, {@code /mode}, commit/void) have no existing web-layer test and are out
 * of scope for this issue.
 */
@WebMvcTest(TaxController.class)
@Import({SecurityConfig.class, TaxControllerRatesTest.FixedClockConfig.class})
class TaxControllerRatesTest {

    /** The application Clock is not auto-configured in a WebMvcTest slice; the advice needs one. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC);
        }

        /**
         * The MockMvc auto-configuration registers every {@code Filter} bean directly, which
         * would run {@code GatewayAuthoritiesFilter} BEFORE the security chain and mark it
         * already-filtered — the in-chain instance then skips, and
         * {@code SecurityContextHolderFilter} wipes the authentication, failing every request
         * with 401. Disable the direct registration so the filter runs exactly where
         * production runs it: inside {@code gatewaySecurityFilterChain}.
         */
        @Bean
        FilterRegistrationBean<com.positivity.security.common.GatewayAuthoritiesFilter>
                gatewayAuthoritiesFilterRegistration(
                        com.positivity.security.common.GatewayAuthoritiesFilter gatewayAuthoritiesFilter) {
            var registration = new FilterRegistrationBean<>(gatewayAuthoritiesFilter);
            registration.setEnabled(false);
            return registration;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaxCalculationService taxCalculationService;

    @MockitoBean
    private TaxProviderLifecycleService lifecycleService;

    @MockitoBean
    private TaxRateLookupService taxRateLookupService;

    /** Authenticates the request the way the API gateway does: X-Authorities / X-User headers. */
    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "web-mvc-tester").header("X-Authorities", String.join(",", authorities));
    }

    @Test
    void getRates_returnsResolvedRates_whenCallerHoldsThePermission() throws Exception {
        TaxRateLookupResponse response = new TaxRateLookupResponse(
                "US",
                "CA",
                "San Francisco",
                "94103",
                LocalDate.parse("2026-08-27"),
                List.of(new TaxRateComponent(TaxJurisdictionType.STATE, new BigDecimal("0.0725"))),
                new BigDecimal("0.0725"),
                "TEST_MODE");
        when(taxRateLookupService.lookupRates(
                        eq("US"), eq("CA"), eq("San Francisco"), eq("94103"), eq(LocalDate.parse("2026-08-27"))))
                .thenReturn(response);

        mockMvc.perform(authed(
                        get("/v1/tax/rates")
                                .param("countryCode", "US")
                                .param("postalCode", "94103")
                                .param("regionCode", "CA")
                                .param("city", "San Francisco")
                                .param("asOf", "2026-08-27"),
                        "tax:rates:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("US"))
                .andExpect(jsonPath("$.combinedRate").value(0.0725))
                .andExpect(jsonPath("$.components[0].jurisdictionType").value("STATE"))
                .andExpect(jsonPath("$.source").value("TEST_MODE"));
    }

    @Test
    void getRates_defaultsAsOfToNull_whenOmitted() throws Exception {
        TaxRateLookupResponse response = new TaxRateLookupResponse(
                "US", null, null, "94103", LocalDate.parse("2026-08-27"), List.of(), BigDecimal.ZERO, "TEST_MODE");
        when(taxRateLookupService.lookupRates(eq("US"), isNull(), isNull(), eq("94103"), isNull()))
                .thenReturn(response);

        mockMvc.perform(authed(
                        get("/v1/tax/rates").param("countryCode", "US").param("postalCode", "94103"), "tax:rates:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postalCode").value("94103"));
    }

    @Test
    void getRates_rejectsMissingPostalCode() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/rates").param("countryCode", "US"), "tax:rates:view"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getRates_rejectsBlankPostalCode() throws Exception {
        mockMvc.perform(authed(
                        get("/v1/tax/rates").param("countryCode", "US").param("postalCode", ""), "tax:rates:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getRates_rejectsInvalidCountryCode() throws Exception {
        mockMvc.perform(authed(
                        get("/v1/tax/rates").param("countryCode", "ZZ").param("postalCode", "94103"), "tax:rates:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getRates_returns501_whenActiveProviderDoesNotSupportRateLookup() throws Exception {
        when(taxRateLookupService.lookupRates(any(), any(), any(), any(), any()))
                .thenThrow(new TaxRateLookupUnsupportedException(
                        "Jurisdiction rate lookup is not supported by the active tax provider (AVALARA)"));

        mockMvc.perform(authed(
                        get("/v1/tax/rates").param("countryCode", "US").param("postalCode", "94103"), "tax:rates:view"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value("TAX_RATE_LOOKUP_UNSUPPORTED"));
    }

    @Test
    void getRates_returnsForbidden_whenCallerLacksThePermission() throws Exception {
        // No ApiError envelope assertion here: pos-tax has no module-local AccessDeniedException
        // handler (unlike e.g. pos-warranty), so a 403 is rendered by Spring Security's own
        // access-denied handling, not this endpoint's own logic.
        mockMvc.perform(authed(
                        get("/v1/tax/rates").param("countryCode", "US").param("postalCode", "94103"), "tax:calculate"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getRates_returnsUnauthorized_whenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/v1/tax/rates").param("countryCode", "US").param("postalCode", "94103"))
                .andExpect(status().isUnauthorized());
    }
}
