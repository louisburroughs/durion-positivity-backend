package com.positivity.supplier.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The masked derivative of a registration number (#2621; Security ruling on #2617, ruling 2). */
@DisplayName("VendorTaxRegistration.last4Of — the ruling-2 rule (#2621 AC 2)")
class VendorTaxRegistrationTest {

    @Test
    @DisplayName("separators are removed, the last four alphanumerics kept")
    void keepsTheLastFourAlphanumerics() {
        assertThat(VendorTaxRegistration.last4Of("000000000RT0001")).isEqualTo("0001");
        assertThat(VendorTaxRegistration.last4Of("FAKE1234")).isEqualTo("1234");
        assertThat(VendorTaxRegistration.last4Of("000-00-1234")).isEqualTo("1234");
        assertThat(VendorTaxRegistration.last4Of("FAKE 00 12AB")).isEqualTo("12AB");
    }

    @Test
    @DisplayName("fewer than 8 alphanumerics is null, so a short number never shows half of itself")
    void underEightIsNull() {
        assertThat(VendorTaxRegistration.last4Of("FAKE-123"))
                .as("7 alphanumerics")
                .isNull();
        assertThat(VendorTaxRegistration.last4Of("--")).isNull();
        assertThat(VendorTaxRegistration.last4Of("")).isNull();
        assertThat(VendorTaxRegistration.last4Of(null)).isNull();
        assertThat(VendorTaxRegistration.last4Of("FAKE-1234")).as("exactly 8").isEqualTo("1234");
    }
}
