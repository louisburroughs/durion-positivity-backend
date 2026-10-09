package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #2615 AC15: the tenant's tax country is an upper-case ISO 3166-1 alpha-2 code in the ledger currency's country. */
@DisplayName("TaxCountry: accounting.tax.country, checked at startup (#2615)")
class TaxCountryTest {

    @Test
    @DisplayName("US with USD starts; a user-assigned fixture code (ZZ) has no currency and starts with any ledger")
    void accepted() {
        assertThat(new TaxCountry("US", new LedgerCurrency("USD")).code()).isEqualTo("US");
        assertThat(new TaxCountry("CA", new LedgerCurrency("CAD")).code()).isEqualTo("CA");
        assertThat(new TaxCountry("ZZ", new LedgerCurrency("USD")).code()).isEqualTo("ZZ");
    }

    @Test
    @DisplayName("us, USA, U1, blank or an unassigned code fails startup naming accounting.tax.country")
    void malformed() {
        for (String code : new String[] {"us", "USA", "U1", "", " US", "QA1", "AB"}) {
            assertThatThrownBy(() -> new TaxCountry(code, new LedgerCurrency("USD")))
                    .as("accounting.tax.country=%s", code)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("accounting.tax.country");
        }
        assertThatThrownBy(() -> new TaxCountry(null, new LedgerCurrency("USD")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accounting.tax.country");
    }

    @Test
    @DisplayName("CA with ledger USD fails startup naming both properties (ADR-0067 PC-9)")
    void currencyMismatch() {
        assertThatThrownBy(() -> new TaxCountry("CA", new LedgerCurrency("USD")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accounting.tax.country")
                .hasMessageContaining("accounting.ledger.base-currency")
                .hasMessageContaining("CAD")
                .hasMessageContaining("USD");
    }
}
