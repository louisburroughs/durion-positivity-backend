package com.positivity.tax.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.tax.internal.config.SecurityConfig;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.service.RegistrationNumberShapes;
import com.positivity.tax.internal.service.TaxCountryProfiles;
import com.positivity.tax.internal.service.TaxEvidenceRules;
import com.positivity.tax.internal.service.TaxPlausibilityService;
import com.positivity.tax.internal.service.TaxProfileFixtures;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Web-layer contract of {@link TaxEvidenceController} (CAP:550 S32b): the evidence-rules read and the
 * plausibility check, wired to the real stubs over fixture configuration (not tax law), through the
 * production security chain. Covers the {@code tax:rates:view} gate, the 400 and 422 envelopes, the
 * made-up country {@code ZZ} (AC 5) and the no-echo rule (AC 6): no body and no log line, captured at
 * DEBUG over the whole logger tree, carries the supplier's registration number.
 */
@WebMvcTest(TaxEvidenceController.class)
@Import({SecurityConfig.class, TaxEvidenceControllerTest.StubConfig.class})
@ImportAutoConfiguration(WebCommonErrorAutoConfiguration.class)
class TaxEvidenceControllerTest {

    /** A number that matches the first country's supplier shape. */
    private static final String GOOD_NUMBER = "987654321RT0001";

    /** Nine bare digits: never well formed. */
    private static final String BAD_NUMBER = "987654321";

    @TestConfiguration
    static class StubConfig {
        /**
         * The fixture properties. Deliberately not a bean: a {@code TaxProperties} bean would be re-bound
         * from the environment (the shipped {@code application.yml}) by the configuration-properties
         * post-processor, replacing the fixture lists.
         */
        private static final TaxProperties PROPERTIES = fixtureProperties();

        private static TaxProperties fixtureProperties() {
            Map<String, String> properties = new LinkedHashMap<>();
            properties.putAll(TaxProfileFixtures.FIRST_COUNTRY);
            properties.putAll(TaxProfileFixtures.FIRST_COUNTRY_STUBS);
            properties.putAll(TaxProfileFixtures.MADE_UP_COUNTRY);
            properties.putAll(TaxProfileFixtures.MADE_UP_COUNTRY_STUBS);
            // The two stub maps each define formats[0]; give ZZ's regime its own entry.
            properties.put("pos.tax.registration.formats[0].regime", "GST_HST");
            properties.put("pos.tax.registration.formats[0].shape", "#########RT####");
            properties.put("pos.tax.registration.formats[2].regime", "R_1");
            properties.put("pos.tax.registration.formats[2].shape", "ZZ#####");
            return TaxProfileFixtures.bind(properties);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        TaxCountryProfiles taxCountryProfiles() {
            return new TaxCountryProfiles(PROPERTIES);
        }

        @Bean
        RegistrationNumberShapes registrationNumberShapes(TaxCountryProfiles profiles) {
            return new RegistrationNumberShapes(PROPERTIES, profiles, new StandardEnvironment());
        }

        @Bean
        TaxEvidenceRules taxEvidenceRules(TaxCountryProfiles profiles, Clock clock) {
            return new TaxEvidenceRules(PROPERTIES, profiles, clock);
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        TaxPlausibilityService taxPlausibilityService(
                TaxCountryProfiles profiles,
                RegistrationNumberShapes shapes,
                TaxEvidenceRules rules,
                Clock clock,
                ObjectProvider<MeterRegistry> meterRegistry) {
            return new TaxPlausibilityService(PROPERTIES, profiles, shapes, rules, clock, meterRegistry);
        }

        /** Same as {@code TaxControllerRatesTest}: run the gateway filter only inside the security chain. */
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

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        previousLevel = root.getLevel();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(appender);
        root.setLevel(previousLevel);
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "web-mvc-tester").header("X-Authorities", String.join(",", authorities));
    }

    private MvcResult check(String body, String... authorities) throws Exception {
        return mockMvc.perform(authed(
                        post("/v1/tax/plausibility-checks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        authorities.length == 0 ? new String[] {"tax:rates:view"} : authorities))
                .andReturn();
    }

    private static String body(String region, String total, String statedTaxes, String number) {
        return "{\"countryCode\":\"CA\",\"regionCode\":\"" + region + "\",\"postalCode\":\"A1A 1A1\","
                + "\"asOf\":\"2026-08-27\",\"currencyCode\":\"CAD\",\"receiptTotal\":" + total
                + (statedTaxes == null ? "" : ",\"statedTaxes\":" + statedTaxes)
                + (number == null ? "" : ",\"supplierRegistrationNumber\":\"" + number + "\"") + "}";
    }

    private List<String> loggedText() {
        List<String> text = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            text.add(event.getFormattedMessage());
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                text.add(String.valueOf(proxy.getMessage()));
            }
        }
        return text;
    }

    // -------------------------------------------------------------------------------------------
    // GET /v1/tax/evidence-rules
    // -------------------------------------------------------------------------------------------

    @Test
    void evidenceRules_returnsTheConfiguredRow() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/evidence-rules").param("countryCode", "CA"), "tax:rates:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("STUB"))
                .andExpect(jsonPath("$.currency").value("CAD"))
                .andExpect(jsonPath("$.asOf").value("2026-08-27"))
                .andExpect(jsonPath("$.rules[0].rule").value("SUPPLIER_REGISTRATION_NUMBER"))
                .andExpect(jsonPath("$.rules[0].fromAmount").value(100.00))
                .andExpect(jsonPath("$.rules[0].appliesTo[0]").value("DRAWER_RECEIPT"))
                .andExpect(jsonPath("$.rules[0].appliesTo[1]").value("VENDOR_BILL"));
    }

    @Test
    void evidenceRules_returnsAnEmptyListForACountryWithoutRules() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/evidence-rules").param("countryCode", "US"), "tax:rates:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules").isEmpty())
                .andExpect(jsonPath("$.source").value("STUB"));
    }

    @Test
    void evidenceRules_answersAMadeUpCountryFromConfiguration() throws Exception {
        mockMvc.perform(authed(
                        get("/v1/tax/evidence-rules").param("countryCode", "ZZ").param("asOf", "2026-02-01"),
                        "tax:rates:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("JPY"))
                .andExpect(jsonPath("$.rules[0].effectiveFrom").value("2026-01-01"));
    }

    @Test
    void evidenceRules_rejectsAMalformedParameter() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/evidence-rules").param("countryCode", "ca"), "tax:rates:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(authed(
                        get("/v1/tax/evidence-rules").param("countryCode", "CA").param("asOf", "27/08/2026"),
                        "tax:rates:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void evidenceRules_returnsForbiddenWithoutThePermission() throws Exception {
        mockMvc.perform(authed(get("/v1/tax/evidence-rules").param("countryCode", "CA"), "tax:calculate"))
                .andExpect(status().isForbidden());
    }

    @Test
    void evidenceRules_returnsUnauthorizedWhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/v1/tax/evidence-rules").param("countryCode", "CA"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------------------------
    // POST /v1/tax/plausibility-checks
    // -------------------------------------------------------------------------------------------

    @Test
    void plausibility_returnsPlausibleWithTheEvidenceAnswers() throws Exception {
        mockMvc.perform(authed(
                        post("/v1/tax/plausibility-checks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("ON", "150.00", "[{\"regime\":\"GST_HST\",\"amount\":6.33}]", null)),
                        "tax:rates:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PLAUSIBLE"))
                .andExpect(jsonPath("$.ratesUsed[0].taxType").value("HST"))
                .andExpect(jsonPath("$.maximums[0].maximum").value(6.38))
                .andExpect(jsonPath("$.supplierRegistrationRequired").value(true))
                .andExpect(jsonPath("$.supplierRegistrationNumberWellFormed").value(nullValue()))
                .andExpect(jsonPath("$.source").value("STUB"));
    }

    @Test
    void plausibility_returns422WithEachMaximum() throws Exception {
        mockMvc.perform(authed(
                        post("/v1/tax/plausibility-checks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("ON", "113.00", "[{\"regime\":\"GST_HST\",\"amount\":4.83}]", null)),
                        "tax:rates:view"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TAX_AMOUNT_IMPLAUSIBLE"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("statedTaxes[0].amount"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(containsString("4.82")));
    }

    @Test
    void plausibility_rejectsANegativeAmount() throws Exception {
        mockMvc.perform(authed(
                        post("/v1/tax/plausibility-checks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("ON", "113.00", "[{\"regime\":\"GST_HST\",\"amount\":-1.00}]", null)),
                        "tax:rates:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("statedTaxes[0].amount"));
    }

    @Test
    void plausibility_rejectsAMissingFieldAndAZeroTotal() throws Exception {
        mockMvc.perform(authed(
                        post("/v1/tax/plausibility-checks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"countryCode\":\"CA\",\"regionCode\":\"ON\",\"currencyCode\":\"CAD\","
                                        + "\"receiptTotal\":0}"),
                        "tax:rates:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void plausibility_rejectsAnUndeclaredRegimeAndAnotherCurrency() throws Exception {
        mockMvc.perform(authed(
                        post("/v1/tax/plausibility-checks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("ON", "113.00", "[{\"regime\":\"NO_SUCH\",\"amount\":1.00}]", null)
                                        .replace("\"CAD\"", "\"EUR\"")),
                        "tax:rates:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("currencyCode"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("statedTaxes[0].regime"));
    }

    @Test
    void plausibility_answersAMadeUpCountryFromConfiguration() throws Exception {
        String body = "{\"countryCode\":\"ZZ\",\"regionCode\":\"Z1\",\"postalCode\":\"00000\",\"asOf\":\"2026-08-27\","
                + "\"currencyCode\":\"JPY\",\"receiptTotal\":1070,\"statedTaxes\":[{\"regime\":\"R_1\",\"amount\":75}],"
                + "\"supplierRegistrationNumber\":\"ZZ12345\"}";

        mockMvc.perform(authed(
                        post("/v1/tax/plausibility-checks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        "tax:rates:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PLAUSIBLE"))
                .andExpect(jsonPath("$.maximums[0].maximum").value(75))
                .andExpect(jsonPath("$.supplierRegistrationNumberWellFormed").value(true));
    }

    @Test
    void plausibility_returnsForbiddenWithoutThePermission() throws Exception {
        assertThat(check(body("ON", "113.00", null, null), "tax:calculate")
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void plausibility_returnsUnauthorizedWhenNotAuthenticated() throws Exception {
        mockMvc.perform(post("/v1/tax/plausibility-checks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ON", "113.00", null, null)))
                .andExpect(status().isUnauthorized());
    }

    /** AC 6: the 200, 400 and 422 bodies and every captured log line never carry the number. */
    @Test
    void plausibility_neverEchoesOrLogsTheNumber() throws Exception {
        MvcResult wellFormed = check(body("ON", "150.00", "[]", GOOD_NUMBER));
        MvcResult malformed = check(body("ON", "150.00", null, BAD_NUMBER));
        MvcResult invalid = check(body("ON", "0", "[{\"regime\":\"GST_HST\",\"amount\":-1}]", BAD_NUMBER));
        MvcResult profileInvalid = check(body("ON", "113.00", "[{\"regime\":\"NO_SUCH\",\"amount\":1}]", BAD_NUMBER));
        MvcResult implausible = check(body("ON", "113.00", "[{\"regime\":\"GST_HST\",\"amount\":50}]", BAD_NUMBER));

        assertThat(wellFormed.getResponse().getStatus()).isEqualTo(200);
        assertThat(wellFormed.getResponse().getContentAsString())
                .contains("\"supplierRegistrationNumberWellFormed\":true");
        assertThat(malformed.getResponse().getStatus()).isEqualTo(200);
        assertThat(malformed.getResponse().getContentAsString())
                .contains("\"supplierRegistrationNumberWellFormed\":false");
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(profileInvalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(implausible.getResponse().getStatus()).isEqualTo(422);

        for (MvcResult result : List.of(wellFormed, malformed, invalid, profileInvalid, implausible)) {
            String content = result.getResponse().getContentAsString();
            assertThat(content).doesNotContain(GOOD_NUMBER).doesNotContain(BAD_NUMBER);
            assertThat(content).doesNotContain("\"supplierRegistrationNumber\"");
        }
        assertThat(appender.list).isNotEmpty();
        assertThat(loggedText()).noneMatch(line -> line.contains(GOOD_NUMBER) || line.contains(BAD_NUMBER));
    }
}
