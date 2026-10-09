package com.positivity.tax.internal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.tax.internal.config.SecurityConfig;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.service.InformationReturnForms;
import com.positivity.tax.internal.service.TaxProfileFixtures;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
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
 * Web-layer contract of {@link InformationReturnFormsController} (CAP:550 #2615, AC16): the stub over fixture
 * configuration for the made-up country {@code ZZ} (not tax law), through the production security chain: the {@code
 * tax:rates:view} gate, an empty list for an unconfigured country, and the 400 for a missing or malformed country code.
 */
@WebMvcTest(InformationReturnFormsController.class)
@Import({SecurityConfig.class, InformationReturnFormsControllerTest.StubConfig.class})
@ImportAutoConfiguration(WebCommonErrorAutoConfiguration.class)
class InformationReturnFormsControllerTest {

    @TestConfiguration
    static class StubConfig {

        /** Not a bean: a TaxProperties bean would be re-bound from the shipped application.yml. */
        private static final TaxProperties PROPERTIES = fixture();

        private static TaxProperties fixture() {
            String zz = "pos.tax.information-returns.ZZ.forms[0].";
            Map<String, String> p = new LinkedHashMap<>();
            p.put(zz + "code", "ZZ_FORM_A");
            p.put(zz + "label", "Form A");
            p.put(zz + "boxes[0].code", "1");
            p.put(zz + "boxes[0].label", "Box one");
            p.put(zz + "payee-id-schemes[0]", "ZZ_BUSINESS_ID");
            p.put(zz + "payee-id-schemes[1]", "ZZ_PERSON_ID");
            return TaxProfileFixtures.bind(p);
        }

        @Bean
        InformationReturnForms informationReturnForms() {
            return new InformationReturnForms(PROPERTIES);
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
    void answersTheConfiguredForms() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/information-return-forms").param("countryCode", "ZZ"), "tax:rates:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("ZZ"))
                .andExpect(jsonPath("$.source").value("STUB"))
                .andExpect(jsonPath("$.forms[0].form").value("ZZ_FORM_A"))
                .andExpect(jsonPath("$.forms[0].label").value("Form A"))
                .andExpect(jsonPath("$.forms[0].boxes[0].box").value("1"))
                .andExpect(jsonPath("$.forms[0].boxes[0].label").value("Box one"))
                .andExpect(jsonPath("$.forms[0].payeeIdSchemes[1]").value("ZZ_PERSON_ID"));
    }

    @Test
    void answersAnEmptyListForAnUnconfiguredCountry() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/information-return-forms").param("countryCode", "MX"), "tax:rates:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("MX"))
                .andExpect(jsonPath("$.forms").isEmpty());
    }

    @Test
    void rejectsAMissingOrMalformedCountryCode() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/information-return-forms"), "tax:rates:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        for (String code : new String[] {"zz", "ZZZ", "Z1"}) {
            mockMvc.perform(authed(
                            get("/v1/tax/information-return-forms").param("countryCode", code), "tax:rates:view"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void returnsForbiddenWithoutThePermission() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/information-return-forms").param("countryCode", "ZZ"), "tax:calculate"))
                .andExpect(status().isForbidden());
    }
}
