package com.positivity.supplier.internal.vendor.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #2621: the records Spring MVC's DEBUG body logs print never carry a number, a reveal reason or {@code last4}
 * (Security ruling on #2617, ruling 2: {@code last4} is never logged).
 */
@DisplayName("Tax-registration records redact their toString (#2621)")
class TaxRegistrationRedactionTest {

    private static final UUID ID = UUID.fromString("01980000-0000-7000-8000-000000000d01");

    @Test
    @DisplayName("TaxRegistrationView, TaxRegistrationDto, TaxIdRevealRequest and TaxIdRevealView redact")
    void redacts() {
        assertThat(new TaxRegistrationView(ID, "SSN", null, "1234").toString())
                .contains("SSN")
                .doesNotContain("1234");
        assertThat(new TaxRegistrationDto(ID, "SSN", "000-00-1234", null).toString())
                .doesNotContain("000-00-1234");
        assertThat(new TaxIdRevealRequest("checking 000-00-1234 per W-9").toString())
                .doesNotContain("000-00-1234")
                .doesNotContain("W-9");
        assertThat(new TaxIdRevealView(ID, "SSN", null, "000-00-1234").toString())
                .doesNotContain("000-00-1234");
    }

    @Test
    @DisplayName("a reason with a control character (U+0000, tab, newline) is 400 VALIDATION_ERROR without echo")
    void reasonRefusesControlCharacters() {
        for (String reason : new String[] {
            "Verifying W-9\u0000 received",
            "Verifying\tW-9 received",
            "Verifying W-9\nreceived today",
            "W-9 check\u007F ok"
        }) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TaxIdRevealRequest(reason))
                    .isInstanceOfSatisfying(
                            com.positivity.supplier.internal.exception.SupplierValidationException.class, refused -> {
                                assertThat(refused.getCode()).isEqualTo("VALIDATION_ERROR");
                                assertThat(refused.getMessage()).doesNotContain("W-9");
                            });
        }
        assertThat(new TaxIdRevealRequest("  Verifying W-9 received  ").reason())
                .isEqualTo("Verifying W-9 received");
    }
}
