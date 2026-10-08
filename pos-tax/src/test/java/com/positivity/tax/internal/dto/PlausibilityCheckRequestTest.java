package com.positivity.tax.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tax.internal.dto.PlausibilityCheckRequest.StatedTax;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CAP:550 S32b AC 6: a logged plausibility request never carries the supplier's registration number. */
class PlausibilityCheckRequestTest {

    @Test
    @DisplayName("toString carries neither the supplier's number nor its field name")
    void toStringRedactsTheNumber() {
        PlausibilityCheckRequest request = new PlausibilityCheckRequest(
                "ZZ",
                "Z1",
                "00000",
                "Springfield",
                LocalDate.parse("2026-08-27"),
                "JPY",
                new BigDecimal("1070"),
                List.of(new StatedTax("R_1", new BigDecimal("75"))),
                "ZZ12345");

        String text = request.toString();

        assertThat(text).doesNotContain("ZZ12345").doesNotContain("supplierRegistrationNumber");
        assertThat(text).contains("supplierNumberSent=true").contains("receiptTotal=1070");
    }
}
