package com.positivity.tax.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.service.ActiveCertificateLookup;
import com.positivity.tax.internal.service.ExemptionResolver;
import com.positivity.tax.internal.service.TestModeTaxCalculator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which address-driven jurisdiction rule wins (story T7), exercised through {@code calculate}.
 *
 * <h2>Why this test exists</h2>
 *
 * {@code resolveRates}' precedence machinery was almost entirely uncovered: 15 of its 28 branches,
 * with the two tie-break helpers — {@code nullSafeEquals} and {@code isAfter} — at 0 of 4 each.
 * That means no test had ever pitted two matching rules against one another; every existing case
 * resolved with at most one candidate. Precedence is the point of the feature: it decides which
 * jurisdiction's rates a sale is taxed at, and a rule losing that should not is money charged at
 * the wrong rate. These tests pin the full ordering — later effectiveFrom first, specificity on
 * ties, configured order last — before the method is split.
 *
 * <p>Each rule carries a distinct STATE rate, so the winner is read off the total tax.
 */
@DisplayName("TestModeTaxCalculator — jurisdiction rule precedence")
class TestModeJurisdictionRuleTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-06-01T00:00:00Z"), ZoneOffset.UTC);

    private TaxProperties properties;
    private TestModeTaxCalculator calculator;

    @BeforeEach
    void setUp() {
        properties = new TaxProperties();
        TaxProperties.TestMode testMode = new TaxProperties.TestMode();
        testMode.setEnabled(true);
        testMode.setDefaultRates(Map.of("STATE", new BigDecimal("0.01")));
        properties.setTestMode(testMode);
        ActiveCertificateLookup lookup = (customerId, certificateId, stateScope, reason, date) -> Optional.empty();
        calculator = new TestModeTaxCalculator(properties, FIXED_CLOCK, new ExemptionResolver(lookup));
    }

    @Nested
    @DisplayName("precedence between matching rules")
    class Precedence {

        @Test
        @DisplayName("the more recently effective rule wins, regardless of configured order")
        void laterEffectiveFromWins() {
            addRule(rule("0.10", "CA", null, null, "2024-01-01"));
            addRule(rule("0.20", "CA", null, null, "2024-03-01"));

            assertThat(totalTaxFor(caRequest())).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("an always-effective rule (null effectiveFrom) loses to any dated one")
        void nullEffectiveFromIsEarliest() {
            addRule(rule("0.10", "CA", null, null, null));
            addRule(rule("0.20", "CA", null, null, "2024-01-01"));

            // Null means "always effective", which for precedence is the earliest possible
            // date: a dated rule was introduced on top of it and must win from that day on.
            assertThat(totalTaxFor(caRequest())).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("a dated rule configured after an always-effective one still wins")
        void datedRuleWinsFromEitherSide() {
            addRule(rule("0.20", "CA", null, null, "2024-01-01"));
            addRule(rule("0.10", "CA", null, null, null));

            assertThat(totalTaxFor(caRequest())).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("on the same effective date, the more specific match wins")
        void specificityBreaksDateTies() {
            addRule(rule("0.10", "CA", null, null, "2024-01-01"));
            addRule(rule("0.20", "CA", "Los Angeles", "900", "2024-01-01"));

            // A city+prefix rule describes this address better than the state-wide one; the
            // state-wide rule is the fallback for the rest of the state, not an equal peer.
            assertThat(totalTaxFor(caRequest())).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("on a full tie, the first configured rule wins")
        void configuredOrderBreaksFullTies() {
            addRule(rule("0.10", "CA", null, null, "2024-01-01"));
            addRule(rule("0.20", "CA", null, null, "2024-01-01"));

            // Deterministic on purpose: the same configuration must always pick the same
            // rule, and "first wins" is the order an operator reading the YAML expects.
            assertThat(totalTaxFor(caRequest())).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("a rule that becomes effective after the transaction date is not a candidate")
        void futureRuleIsSkipped() {
            addRule(rule("0.10", "CA", null, null, "2024-01-01"));
            addRule(rule("0.20", "CA", null, null, "2024-07-01"));

            // The clock is 2024-06-01; the July rule exists but is not yet law for this sale.
            assertThat(totalTaxFor(caRequest())).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("a rule with no rates is not a candidate, even when it matches best")
        void rateLessRuleIsSkipped() {
            addRule(rule(null, "CA", "Los Angeles", "900", "2024-03-01"));
            addRule(rule("0.10", "CA", null, null, "2024-01-01"));

            // A rule with nothing to charge cannot win: selecting it would tax the sale at
            // nothing on the strength of an empty stanza.
            assertThat(totalTaxFor(caRequest())).isEqualByComparingTo("10.00");
        }
    }

    @Nested
    @DisplayName("address matching")
    class Matching {

        @Test
        @DisplayName("state code matches case-insensitively")
        void stateMatchesCaseInsensitively() {
            addRule(rule("0.10", "ca", null, null, null));

            assertThat(totalTaxFor(caRequest())).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("a postal-code prefix matches by prefix, not equality")
        void postalPrefixMatches() {
            addRule(rule("0.10", null, null, "900", null));

            assertThat(totalTaxFor(caRequest())).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("a non-matching prefix rejects the rule and resolution falls back to defaults")
        void postalPrefixRejects() {
            addRule(rule("0.10", null, null, "941", null));

            // 90001 does not start with 941 (San Francisco); falling back to the 1% default
            // is correct — a rule for another city must not price this sale.
            assertThat(totalTaxFor(caRequest())).isEqualByComparingTo("1.00");
        }

        @Test
        @DisplayName("a rule for another state does not match")
        void otherStateRejects() {
            addRule(rule("0.10", "TX", null, null, null));

            assertThat(totalTaxFor(caRequest())).isEqualByComparingTo("1.00");
        }

        @Test
        @DisplayName("blank facets are wildcards, not literal matches")
        void blankFacetsAreWildcards() {
            addRule(rule("0.10", " ", " ", " ", null));

            assertThat(totalTaxFor(caRequest())).isEqualByComparingTo("10.00");
        }
    }

    @Nested
    @DisplayName("per-rule exempt categories")
    class ExemptCategories {

        @Test
        @DisplayName("exempt categories match case-insensitively, and blank entries are dropped")
        void exemptCategoriesAreNormalized() {
            TaxProperties.JurisdictionRule winning = rule("0.10", "CA", null, null, null);
            winning.setExemptCategories(new java.util.LinkedHashSet<>(List.of("labor", " ", "")));
            addRule(winning);

            TaxCalculationRequest request = TaxCalculationRequest.builder()
                    .lineItems(
                            List.of(lineItem("1", "Alignment", "100", "LABOR"), lineItem("2", "Tire", "100", "GOODS")))
                    .destinationAddress(caAddress())
                    .build();

            // Only the goods line is taxed: the rule's lower-case "labor" must exempt the
            // upper-case LABOR line, and the blank entries must not exempt everything.
            assertThat(calculator.calculate(request).getTotalTax()).isEqualByComparingTo("10.00");
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private void addRule(TaxProperties.JurisdictionRule rule) {
        properties.getTestMode().getJurisdictions().add(rule);
    }

    private static TaxProperties.JurisdictionRule rule(
            String stateRate, String stateCode, String city, String postalPrefix, String effectiveFrom) {
        TaxProperties.JurisdictionRule rule = new TaxProperties.JurisdictionRule();
        TaxProperties.JurisdictionMatch match = new TaxProperties.JurisdictionMatch();
        match.setStateCode(stateCode);
        match.setCity(city);
        match.setPostalCodePrefix(postalPrefix);
        rule.setMatch(match);
        if (stateRate != null) {
            rule.setRates(Map.of("STATE", new BigDecimal(stateRate)));
        } else {
            rule.setRates(Map.of());
        }
        if (effectiveFrom != null) {
            rule.setEffectiveFrom(java.time.LocalDate.parse(effectiveFrom));
        }
        return rule;
    }

    private BigDecimal totalTaxFor(TaxCalculationRequest request) {
        return calculator.calculate(request).getTotalTax();
    }

    private static TaxCalculationRequest caRequest() {
        return TaxCalculationRequest.builder()
                .lineItems(List.of(lineItem("1", "Part", "100", null)))
                .destinationAddress(caAddress())
                .build();
    }

    private static TaxCalculationRequest.TaxAddress caAddress() {
        return TaxCalculationRequest.TaxAddress.builder()
                .postalCode("90001")
                .regionCode("CA")
                .city("Los Angeles")
                .countryCode("US")
                .build();
    }

    private static TaxLineItem lineItem(String id, String desc, String price, String category) {
        return TaxLineItem.builder()
                .lineItemId(id)
                .description(desc)
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal(price))
                .taxCategory(category)
                .build();
    }
}
