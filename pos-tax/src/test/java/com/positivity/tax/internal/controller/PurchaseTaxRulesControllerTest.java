package com.positivity.tax.internal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.tax.internal.config.SecurityConfig;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.service.PurchaseTaxRules;
import com.positivity.tax.internal.service.TaxProfileFixtures;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Web-layer contract of {@link PurchaseTaxRulesController} (CAP:550 S43, AC 12): the stub over fixture configuration
 * for the made-up country {@code ZZ} (not tax law), through the production security chain: the {@code tax:rates:view}
 * gate, {@code configured = false} for an unconfigured country, and the 400 for a missing or malformed country code or
 * date.
 */
@WebMvcTest(PurchaseTaxRulesController.class)
@Import({SecurityConfig.class, PurchaseTaxRulesControllerTest.StubConfig.class})
@ImportAutoConfiguration(WebCommonErrorAutoConfiguration.class)
class PurchaseTaxRulesControllerTest {

    @TestConfiguration
    static class StubConfig {

        private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);

        /** Not a bean: a TaxProperties bean would be re-bound from the shipped application.yml. */
        private static final TaxProperties PROPERTIES = fixture();

        private static TaxProperties fixture() {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("pos.tax.purchase-rules.ZZ.tax-on-resale-goods", "HOLD");
            p.put("pos.tax.purchase-rules.ZZ.self-assess-untaxed-expenses", "true");
            return TaxProfileFixtures.bind(p);
        }

        @Bean
        Clock clock() {
            return CLOCK;
        }

        @Bean
        PurchaseTaxRules purchaseTaxRules() {
            return new PurchaseTaxRules(PROPERTIES, CLOCK);
        }

        /** Same as {@code TaxEvidenceControllerTest}: run the gateway filter only inside the security chain. */
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

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "pos-accounting").header("X-Authorities", String.join(",", authorities));
    }

    @Test
    void answersTheConfiguredRules() throws Exception {
        mockMvc.perform(authed(
                        get("/v1/tax/purchase-rules").param("countryCode", "ZZ").param("asOf", "2026-09-30"),
                        "tax:rates:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("ZZ"))
                .andExpect(jsonPath("$.asOf").value("2026-09-30"))
                .andExpect(jsonPath("$.source").value("STUB"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.taxOnResaleGoods").value("HOLD"))
                .andExpect(jsonPath("$.selfAssessUntaxedExpenses").value(true));
    }

    @Test
    void answersConfiguredFalseForAnUnconfiguredCountryOnToday() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/purchase-rules").param("countryCode", "MX"), "tax:rates:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("MX"))
                .andExpect(jsonPath("$.asOf").value("2026-10-08"))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.taxOnResaleGoods").value("ALLOW"))
                .andExpect(jsonPath("$.selfAssessUntaxedExpenses").value(false));
    }

    @Test
    void rejectsAMissingOrMalformedCountryCodeOrDate() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/purchase-rules"), "tax:rates:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        for (String code : new String[] {"zz", "Zz", "ZZZ", "Z1"}) {
            mockMvc.perform(authed(get("/v1/tax/purchase-rules").param("countryCode", code), "tax:rates:view"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mockMvc.perform(authed(
                        get("/v1/tax/purchase-rules").param("countryCode", "ZZ").param("asOf", "2026-13-01"),
                        "tax:rates:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void returnsForbiddenWithoutThePermission() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/purchase-rules").param("countryCode", "ZZ"), "tax:calculate"))
                .andExpect(status().isForbidden());
    }
}
