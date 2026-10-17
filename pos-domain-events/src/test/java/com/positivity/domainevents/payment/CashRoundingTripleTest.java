package com.positivity.domainevents.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-0067 A8 (#2662, ruling D3): the shared triple check both pos-invoice and pos-accounting rely on. */
class CashRoundingTripleTest {

    private static PaymentSettledV1 fact(String method, String amount, String settled, String rounding, String inc) {
        return new PaymentSettledV1(
                UUID.fromString("00000000-0000-0000-0000-0000000000f1"),
                UUID.fromString("00000000-0000-0000-0000-0000000000f2"),
                "INV-1",
                null,
                null,
                "00000000-0000-0000-0000-0000000000f3",
                method,
                new BigDecimal(amount),
                "CAD",
                "register",
                null,
                Instant.parse("2026-10-17T12:00:00Z"),
                settled == null ? null : new BigDecimal(settled),
                rounding == null ? null : new BigDecimal(rounding),
                inc == null ? null : new BigDecimal(inc));
    }

    @Test
    void roundedUpAndDownAreValid() {
        assertThat(CashRoundingTriple.problem(fact("CASH", "9.98", "10.00", "0.02", "0.05"))).isEmpty();
        assertThat(CashRoundingTriple.problem(fact("CASH", "10.02", "10.00", "-0.02", "0.05"))).isEmpty();
        assertThat(CashRoundingTriple.problem(fact("CASH", "149.925", "149.95", "0.025", "0.05"))).isEmpty();
        assertThat(CashRoundingTriple.problem(fact("CASH", "19.50", "20", "0.50", "1"))).isEmpty();
    }

    @Test
    void noIncrementNeedsExactAgreement() {
        assertThat(CashRoundingTriple.problem(fact("CASH", "9.98", "9.98", "0.00", null))).isEmpty();
        assertThat(CashRoundingTriple.problem(fact("CASH", "9.98", "10.00", "0.02", null)))
                .get()
                .asString()
                .contains("settledAmount");
        assertThat(CashRoundingTriple.problem(fact("CASH", "9.98", "9.98", "0.01", null)))
                .get()
                .asString()
                .contains("cashRoundingAmount");
    }

    @Test
    void invalidTriplesNameTheRule() {
        assertThat(CashRoundingTriple.problem(fact("CASH", "9.98", "9.95", "-0.03", "0.05")))
                .get()
                .asString()
                .contains("HALF_UP");
        assertThat(CashRoundingTriple.problem(fact("CASH", "9.98", "10.00", "0.03", "0.05")))
                .get()
                .asString()
                .contains("minus amount");
        assertThat(CashRoundingTriple.problem(fact("CASH", "9.98", "9.98", "0.00", "0")))
                .get()
                .asString()
                .contains("greater than zero");
        assertThat(CashRoundingTriple.problem(fact("CARD", "9.98", "10.00", "0.02", "0.05")))
                .get()
                .asString()
                .contains("CARD");
    }

    @Test
    void aFactWithoutTheFieldsCarriesNothing() {
        PaymentSettledV1 legacy = fact("CASH", "9.98", null, null, null);
        assertThat(CashRoundingTriple.present(legacy)).isFalse();
        assertThat(CashRoundingTriple.problem(legacy)).isEmpty();
        assertThat(CashRoundingTriple.roundingOrZero(legacy)).isZero();
        assertThat(CashRoundingTriple.roundingOrZero(fact("CASH", "9.98", "10.00", "0.02", "0.05")))
                .isEqualByComparingTo("0.02");
    }
}
