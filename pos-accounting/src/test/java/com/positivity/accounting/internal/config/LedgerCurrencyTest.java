package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ledger currency is an ISO 4217 code (#2577; ADR-0067 R-3): {@code FlywayConfig} binds V15's {@code
 * ledger_currency} placeholder from it, so a three-letter code the ISO list does not know must fail startup before any
 * migration could backfill it into {@code register_float}.
 */
@DisplayName("LedgerCurrency: an ISO 4217 code, or no startup (#2577)")
class LedgerCurrencyTest {

    @Test
    @DisplayName("an ISO code is accepted, trimmed and upper-cased, and becomes the Flyway placeholder")
    void isoCodeBecomesThePlaceholder() {
        LedgerCurrency ledger = new LedgerCurrency(" cad ");

        assertThat(ledger.code()).isEqualTo("CAD");
        assertThat(FlywayConfig.placeholders(ledger)).containsExactly(java.util.Map.entry("ledger_currency", "CAD"));
    }

    @Test
    @DisplayName("three letters the ISO 4217 list does not know (XYZ) fail with a clear message, as do malformed codes")
    void nonIsoCodeFailsStartup() {
        for (String code : new String[] {"XYZ", "ABC", "US", "USDX", "U5D", ""}) {
            assertThatThrownBy(() -> new LedgerCurrency(code))
                    .as("accounting.ledger.base-currency=%s", code)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("accounting.ledger.base-currency must be an ISO 4217 currency code");
        }
    }
}
