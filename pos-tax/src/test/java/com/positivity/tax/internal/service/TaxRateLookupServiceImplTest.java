package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.tax.common.dto.TaxRateComponent;
import com.positivity.tax.common.dto.TaxRateLookupResponse;
import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.exception.TaxRateLookupUnsupportedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TaxRateLookupServiceImpl} (issue #1522).
 * <p>
 * Test mode resolution is exercised against a real {@link TestModeRateResolver} (the
 * extracted collaborator {@link TestModeTaxCalculator} also uses), rather than mocked, so these
 * tests double as evidence the refactor did not change resolution behavior.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaxRateLookupServiceImpl Tests")
class TaxRateLookupServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private AvalaraTaxProvider avalaraProvider;

    private TaxProperties properties;
    private TaxRateLookupServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new TaxProperties();
        TaxProperties.TestMode testMode = new TaxProperties.TestMode();
        testMode.setEnabled(true);
        Map<String, BigDecimal> defaultRates = new HashMap<>();
        defaultRates.put("STATE", new BigDecimal("0.0725"));
        testMode.setDefaultRates(defaultRates);
        properties.setTestMode(testMode);

        service = buildService(properties);
    }

    private TaxRateLookupServiceImpl buildService(TaxProperties props) {
        TestModeRateResolver rateResolver = new TestModeRateResolver(props);
        TestModeTaxProvider testModeProvider = new TestModeTaxProvider(mock(TestModeTaxCalculator.class));
        ExternalTaxProvider externalProvider = new ExternalTaxProvider(null);
        TaxProviderSelector selector =
                new TaxProviderSelector(props, testModeProvider, externalProvider, avalaraProvider);
        return new TaxRateLookupServiceImpl(props, selector, rateResolver, FIXED_CLOCK);
    }

    @Test
    @DisplayName("test mode: falls back to defaultRates when no schedule or jurisdiction rule matches")
    void fallsBackToDefaultRates() {
        TaxRateLookupResponse response =
                service.lookupRates("US", "CA", "Los Angeles", "90001", LocalDate.parse("2026-08-27"));

        assertThat(response.countryCode()).isEqualTo("US");
        assertThat(response.regionCode()).isEqualTo("CA");
        assertThat(response.postalCode()).isEqualTo("90001");
        assertThat(response.components())
                .containsExactly(new TaxRateComponent(TaxJurisdictionType.STATE, new BigDecimal("0.0725")));
        assertThat(response.combinedRate()).isEqualByComparingTo("0.0725");
        assertThat(response.source()).isEqualTo("TEST_MODE");
    }

    @Test
    @DisplayName("asOf defaults to the clock's today when omitted")
    void asOfDefaultsToClockToday() {
        TaxRateLookupResponse response = service.lookupRates("US", "CA", "Los Angeles", "90001", null);

        assertThat(response.asOf()).isEqualTo(LocalDate.parse("2026-08-27"));
    }

    @Test
    @DisplayName("rateSchedule effective-dating: asOf before an entry uses the prior rates")
    void rateScheduleAsOfBeforeEntry() {
        configureSchedule(scheduleEntry("2026-06-01", Map.of("STATE", new BigDecimal("0.05"))));

        TaxRateLookupResponse response =
                service.lookupRates("US", "CA", "Los Angeles", "90001", LocalDate.parse("2026-01-01"));

        // Before the schedule entry's effectiveFrom: falls back to defaultRates (7.25%).
        assertThat(response.combinedRate()).isEqualByComparingTo("0.0725");
    }

    @Test
    @DisplayName("rateSchedule effective-dating: asOf on/after an entry uses that entry's rates")
    void rateScheduleAsOfAfterEntry() {
        configureSchedule(scheduleEntry("2026-06-01", Map.of("STATE", new BigDecimal("0.05"))));

        TaxRateLookupResponse response =
                service.lookupRates("US", "CA", "Los Angeles", "90001", LocalDate.parse("2026-06-01"));

        assertThat(response.combinedRate()).isEqualByComparingTo("0.05");
    }

    @Test
    @DisplayName("jurisdiction rule specificity: a state+city+postal-prefix rule beats a state-only rule")
    void jurisdictionRuleSpecificityWins() {
        TaxProperties.JurisdictionRule stateOnly = rule("CA", null, null, Map.of("STATE", new BigDecimal("0.06")));
        TaxProperties.JurisdictionRule specific =
                rule("CA", "Los Angeles", "900", Map.of("STATE", new BigDecimal("0.10")));
        properties.getTestMode().setJurisdictions(new java.util.ArrayList<>(List.of(stateOnly, specific)));

        TaxRateLookupResponse response =
                service.lookupRates("US", "CA", "Los Angeles", "90001", LocalDate.parse("2026-08-27"));

        assertThat(response.combinedRate()).isEqualByComparingTo("0.10");
    }

    @Test
    @DisplayName("non-test provider (AVALARA): lookup fails loudly with TaxRateLookupUnsupportedException")
    void nonTestProviderThrowsUnsupported() {
        when(avalaraProvider.providerName()).thenReturn("AVALARA");
        properties.getTestMode().setEnabled(false);
        properties.setProvider(TaxProperties.Provider.AVALARA);

        assertThatThrownBy(() -> service.lookupRates("US", "CA", "Los Angeles", "90001", LocalDate.parse("2026-08-27")))
                .isInstanceOf(TaxRateLookupUnsupportedException.class)
                .hasMessageContaining("AVALARA");
    }

    // Helper methods

    private void configureSchedule(TaxProperties.RateScheduleEntry... entries) {
        properties.getTestMode().setRateSchedule(new java.util.ArrayList<>(List.of(entries)));
    }

    private TaxProperties.RateScheduleEntry scheduleEntry(String effectiveFrom, Map<String, BigDecimal> rates) {
        TaxProperties.RateScheduleEntry entry = new TaxProperties.RateScheduleEntry();
        entry.setEffectiveFrom(LocalDate.parse(effectiveFrom));
        entry.setRates(new HashMap<>(rates));
        return entry;
    }

    private TaxProperties.JurisdictionRule rule(
            String stateCode, String city, String postalPrefix, Map<String, BigDecimal> rates) {
        TaxProperties.JurisdictionRule rule = new TaxProperties.JurisdictionRule();
        TaxProperties.JurisdictionMatch match = new TaxProperties.JurisdictionMatch();
        match.setStateCode(stateCode);
        match.setCity(city);
        match.setPostalCodePrefix(postalPrefix);
        rule.setMatch(match);
        rule.setRates(new HashMap<>(rates));
        return rule;
    }
}
