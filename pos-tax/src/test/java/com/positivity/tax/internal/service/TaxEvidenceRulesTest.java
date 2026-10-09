package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.dto.EvidenceRulesResponse;
import com.positivity.tax.internal.enums.EvidenceDocumentType;
import com.positivity.tax.internal.enums.EvidenceRule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * CAP:550 S32b AC 3: the evidence-rule stub. Every invalid row fails startup naming the property; the
 * shipped row and the read answer from configuration.
 */
@DisplayName("TaxEvidenceRules (CAP:550 S32b)")
class TaxEvidenceRulesTest {

    private static final String RULES = "pos.tax.countries.CA.evidence-rules";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC);

    private static Map<String, String> stubsWith(Consumer<Map<String, String>> change) {
        Map<String, String> properties = new LinkedHashMap<>(TaxProfileFixtures.FIRST_COUNTRY);
        properties.putAll(TaxProfileFixtures.FIRST_COUNTRY_STUBS);
        change.accept(properties);
        return properties;
    }

    private static TaxEvidenceRules rules(Map<String, String> properties) {
        TaxProperties bound = TaxProfileFixtures.bind(properties);
        return new TaxEvidenceRulesImpl(bound, new TaxCountryProfiles(bound), CLOCK);
    }

    static Stream<Arguments> invalidRules() {
        return Stream.of(
                Arguments.of(
                        "an unknown rule",
                        stubsWith(p -> p.put(RULES + "[0].rule", "RECEIPT_PHOTO")),
                        RULES + "[0].rule"),
                Arguments.of("a missing rule", stubsWith(p -> p.remove(RULES + "[0].rule")), RULES + "[0].rule"),
                Arguments.of(
                        "an unknown applies-to value",
                        stubsWith(p -> p.put(RULES + "[0].applies-to[1]", "INVOICE")),
                        RULES + "[0].applies-to[1]"),
                Arguments.of(
                        "a from-amount of 0",
                        stubsWith(p -> p.put(RULES + "[0].from-amount", "0")),
                        RULES + "[0].from-amount"),
                Arguments.of(
                        "a negative from-amount",
                        stubsWith(p -> p.put(RULES + "[0].from-amount", "-1.00")),
                        RULES + "[0].from-amount"),
                Arguments.of(
                        "a missing from-amount",
                        stubsWith(p -> p.remove(RULES + "[0].from-amount")),
                        RULES + "[0].from-amount"),
                Arguments.of(
                        "a from-amount finer than the currency",
                        stubsWith(p -> p.put(RULES + "[0].from-amount", "100.001")),
                        RULES + "[0].from-amount"),
                Arguments.of(
                        "an empty applies-to",
                        stubsWith(p -> {
                            p.remove(RULES + "[0].applies-to[0]");
                            p.remove(RULES + "[0].applies-to[1]");
                        }),
                        RULES + "[0].applies-to"),
                Arguments.of(
                        "an end before the start",
                        stubsWith(p -> {
                            p.put(RULES + "[0].effective-from", "2026-06-01");
                            p.put(RULES + "[0].effective-to", "2026-05-31");
                        }),
                        RULES + "[0].effective-to"),
                Arguments.of(
                        "two rows of one rule and type in effect on the same date",
                        stubsWith(p -> {
                            p.put(RULES + "[0].effective-to", "2026-06-30");
                            p.put(RULES + "[1].rule", "SUPPLIER_REGISTRATION_NUMBER");
                            p.put(RULES + "[1].from-amount", "200.00");
                            p.put(RULES + "[1].applies-to[0]", "VENDOR_BILL");
                            p.put(RULES + "[1].effective-from", "2026-06-30");
                        }),
                        RULES + "[1]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRules")
    @DisplayName("an invalid evidence rule fails startup, naming the property")
    void invalidRuleFailsStartup(String label, Map<String, String> properties, String property) {
        assertThatThrownBy(() -> rules(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Invalid tax configuration " + property + ":");
    }

    @Test
    @DisplayName("rows of one rule that do not overlap, or cover other types, start")
    void successiveRowsStart() {
        TaxEvidenceRules rules = rules(stubsWith(p -> {
            p.put(RULES + "[0].effective-to", "2026-06-30");
            p.put(RULES + "[1].rule", "SUPPLIER_REGISTRATION_NUMBER");
            p.put(RULES + "[1].from-amount", "200.00");
            p.put(RULES + "[1].applies-to[0]", "DRAWER_RECEIPT");
            p.put(RULES + "[1].effective-from", "2026-07-01");
        }));

        assertThat(rules.inEffect("CA", LocalDate.parse("2026-06-30")))
                .singleElement()
                .satisfies(rule -> assertThat(rule.fromAmount()).isEqualByComparingTo("100.00"));
        assertThat(rules.inEffect("CA", LocalDate.parse("2026-07-01")))
                .singleElement()
                .satisfies(rule -> assertThat(rule.fromAmount()).isEqualByComparingTo("200.00"));
    }

    @Test
    @DisplayName("AC 3: the shipped row is SUPPLIER_REGISTRATION_NUMBER from 100.00 for drawer receipts and bills")
    void shippedRow() throws Exception {
        TaxProperties shipped = RegistrationNumberShapesTest.shipped();
        TaxEvidenceRules rules = new TaxEvidenceRulesImpl(shipped, new TaxCountryProfiles(shipped), CLOCK);

        EvidenceRulesResponse response = rules.read("CA", null);

        assertThat(response.source()).isEqualTo("STUB");
        assertThat(response.currency()).isEqualTo("CAD");
        assertThat(response.asOf()).isEqualTo(LocalDate.parse("2026-08-27"));
        assertThat(response.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.rule()).isEqualTo("SUPPLIER_REGISTRATION_NUMBER");
            assertThat(rule.fromAmount()).isEqualByComparingTo("100.00");
            assertThat(rule.appliesTo()).containsExactly("DRAWER_RECEIPT", "VENDOR_BILL");
            assertThat(rule.effectiveFrom()).isNull();
            assertThat(rule.effectiveTo()).isNull();
        });
        assertThat(rules.requires(
                        "CA",
                        EvidenceRule.SUPPLIER_REGISTRATION_NUMBER,
                        EvidenceDocumentType.DRAWER_RECEIPT,
                        new BigDecimal("150.00"),
                        LocalDate.parse("2026-08-27")))
                .isTrue();
        assertThat(rules.requires(
                        "CA",
                        EvidenceRule.SUPPLIER_REGISTRATION_NUMBER,
                        EvidenceDocumentType.DRAWER_RECEIPT,
                        new BigDecimal("80.00"),
                        LocalDate.parse("2026-08-27")))
                .isFalse();
        // The threshold is inclusive.
        assertThat(rules.requires(
                        "CA",
                        EvidenceRule.SUPPLIER_REGISTRATION_NUMBER,
                        EvidenceDocumentType.VENDOR_BILL,
                        new BigDecimal("100.00"),
                        LocalDate.parse("2026-08-27")))
                .isTrue();
    }

    @Test
    @DisplayName("AC 3: a country with no rule, or no profile, reads an empty list")
    void countryWithoutRules() {
        TaxEvidenceRules rules = rules(stubsWith(p -> {}));

        EvidenceRulesResponse response = rules.read("US", LocalDate.parse("2026-08-27"));

        assertThat(response.rules()).isEmpty();
        assertThat(response.currency()).isNull();
        assertThat(response.source()).isEqualTo("STUB");
    }

    @Test
    @DisplayName("AC 5: a made-up country's rule is configuration only, dated and in its own currency")
    void madeUpCountry() {
        Map<String, String> properties = new LinkedHashMap<>(TaxProfileFixtures.MADE_UP_COUNTRY);
        properties.putAll(TaxProfileFixtures.MADE_UP_COUNTRY_STUBS);
        TaxEvidenceRules rules = rules(properties);

        assertThat(rules.read("ZZ", LocalDate.parse("2025-12-31")).rules()).isEmpty();
        EvidenceRulesResponse response = rules.read("ZZ", LocalDate.parse("2026-01-01"));
        assertThat(response.currency()).isEqualTo("JPY");
        assertThat(response.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.fromAmount()).isEqualByComparingTo("1000");
            assertThat(rule.appliesTo()).containsExactly("DRAWER_RECEIPT");
        });
    }
}
