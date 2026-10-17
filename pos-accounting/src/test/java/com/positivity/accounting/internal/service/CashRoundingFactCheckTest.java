package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.payment.PaymentSettledV1;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-0067 A8 (#2662, item 12, AC 20): the triple pos-accounting accepts and the ones it holds. */
class CashRoundingFactCheckTest {

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
    void ac20_cashRoundedUpIsValidAndCarriesThePositiveRounding() {
        PaymentSettledV1 fact = fact("CASH", "9.98", "10.00", "0.02", "0.05");
        assertThat(CashRoundingFactCheck.problem(fact)).isEmpty();
        assertThat(CashRoundingFactCheck.roundingOf(fact)).isEqualByComparingTo("0.02");
    }

    @Test
    void cashRoundedDownIsValidWithANegativeRounding() {
        assertThat(CashRoundingFactCheck.problem(fact("CASH", "10.02", "10.00", "-0.02", "0.05")))
                .isEmpty();
    }

    @Test
    void ac20_aRoundingOfThreeCentsIsInvalid() {
        assertThat(CashRoundingFactCheck.problem(fact("CASH", "9.98", "9.95", "-0.03", "0.05")))
                .isPresent()
                .get()
                .asString()
                .contains("HALF_UP");
    }

    @Test
    void ac20_roundingOnACardTenderIsInvalid() {
        assertThat(CashRoundingFactCheck.problem(fact("CARD", "9.98", "10.00", "0.02", "0.05")))
                .isPresent()
                .get()
                .asString()
                .contains("CARD");
    }

    @Test
    void aTripleThatDoesNotAddUpIsInvalid() {
        assertThat(CashRoundingFactCheck.problem(fact("CASH", "9.98", "10.00", "0.03", "0.05")))
                .isPresent();
    }

    @Test
    void aRoundingWithoutAnIncrementIsInvalid() {
        assertThat(CashRoundingFactCheck.problem(fact("CASH", "9.98", "10.00", "0.02", null)))
                .isPresent();
    }

    @Test
    void aCashTenderWithoutAnIncrementAndNoRoundingIsValid() {
        PaymentSettledV1 fact = fact("CASH", "9.98", "9.98", "0.00", null);
        assertThat(CashRoundingFactCheck.problem(fact)).isEmpty();
        assertThat(CashRoundingFactCheck.roundingOf(fact)).isZero();
    }

    @Test
    void aFactWithoutTheFieldsCarriesNoRounding() {
        PaymentSettledV1 legacy = fact("CASH", "9.98", null, null, null);
        assertThat(CashRoundingFactCheck.problem(legacy)).isEmpty();
        assertThat(CashRoundingFactCheck.roundingOf(legacy)).isZero();
        assertThat(CashRoundingFactCheck.problem(fact("CARD", "150.00", null, null, null)))
                .isEmpty();
    }
}
