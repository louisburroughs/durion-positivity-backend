package com.positivity.tax.internal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.tax.internal.config.SecurityConfig;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.service.TaxProfileFixtures;
import com.positivity.tax.internal.service.TaxRegistrationSchemes;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Web-layer contract of {@link TaxRegistrationSchemesController} (CAP:550 #2623, AC 1): the stub over fixture
 * configuration for the made-up country {@code ZZ} (not tax law), through the production security chain: the
 * {@code tax:rates:view} gate, every country without a parameter, an empty list for an unconfigured country, and
 * the 400 for a malformed country code.
 */
@WebMvcTest(TaxRegistrationSchemesController.class)
@Import({SecurityConfig.class, TaxRegistrationSchemesControllerTest.StubConfig.class})
@ImportAutoConfiguration(WebCommonErrorAutoConfiguration.class)
class TaxRegistrationSchemesControllerTest {

    @TestConfiguration
    static class StubConfig {
        /** Not a bean: a TaxProperties bean would be re-bound from the shipped application.yml. */
        private static final TaxProperties PROPERTIES = fixture();

        private static TaxProperties fixture() {
            String zz = "pos.tax.registration-schemes.ZZ";
            Map<String, String> p = new LinkedHashMap<>();
            p.put(zz + "[0].scheme", "ZZ_REGIONAL");
            p.put(zz + "[0].label", "Regional registration");
            p.put(zz + "[0].holder-type", "BUSINESS");
            p.put(zz + "[0].regional", "true");
            p.put(zz + "[0].regions[0]", "QC");
            p.put(zz + "[1].scheme", "ZZ_PERSON_ID");
            p.put(zz + "[1].label", "Person id");
            p.put(zz + "[1].holder-type", "PERSON");
            p.put(zz + "[1].regional", "false");
            return TaxProfileFixtures.bind(p);
        }

        @Bean
        java.time.Clock clock() {
            return java.time.Clock.fixed(java.time.Instant.parse("2026-10-17T12:00:00Z"), java.time.ZoneOffset.UTC);
        }

        @Bean
        TaxRegistrationSchemes taxRegistrationSchemes() {
            return new TaxRegistrationSchemes(PROPERTIES);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "pos-supplier-service").header("X-Authorities", String.join(",", authorities));
    }

    @Test
    void answersTheConfiguredSchemesOfACountry() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/registration-schemes").param("countryCode", "ZZ"), "tax:rates:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("STUB"))
                .andExpect(jsonPath("$.schemes[0].scheme").value("ZZ_PERSON_ID"))
                .andExpect(jsonPath("$.schemes[0].countryCode").value("ZZ"))
                .andExpect(jsonPath("$.schemes[0].holderType").value("PERSON"))
                .andExpect(jsonPath("$.schemes[0].regional").value(false))
                .andExpect(jsonPath("$.schemes[0].regions").isEmpty())
                .andExpect(jsonPath("$.schemes[1].scheme").value("ZZ_REGIONAL"))
                .andExpect(jsonPath("$.schemes[1].label").value("Regional registration"))
                .andExpect(jsonPath("$.schemes[1].regional").value(true))
                .andExpect(jsonPath("$.schemes[1].regions[0]").value("QC"));
    }

    @Test
    void answersEveryCountryWithoutAParameter() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/registration-schemes"), "tax:rates:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemes.length()").value(2));
    }

    @Test
    void answersAnEmptyListForAnUnconfiguredCountry() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/registration-schemes").param("countryCode", "MX"), "tax:rates:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("STUB"))
                .andExpect(jsonPath("$.schemes").isEmpty());
    }

    @Test
    void rejectsAMalformedCountryCode() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/registration-schemes").param("countryCode", "U1"), "tax:rates:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(authed(get("/v1/tax/registration-schemes").param("countryCode", "us"), "tax:rates:view"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesWithoutTheAuthority() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/registration-schemes"), "tax:calculate"))
                .andExpect(status().isForbidden());
    }
}
