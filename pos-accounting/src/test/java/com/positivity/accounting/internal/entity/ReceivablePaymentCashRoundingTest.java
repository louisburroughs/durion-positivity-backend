package com.positivity.accounting.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** ADR-0067 A8 (#2662): the tender's rounding is booked by exactly one cash-receipt entry. */
class ReceivablePaymentCashRoundingTest {

    private static ReceivablePayment withRounding(String rounding) {
        ReceivablePayment payment = new ReceivablePayment();
        payment.setCashRoundingAmount(rounding == null ? null : new BigDecimal(rounding));
        return payment;
    }

    @Test
    void theFirstEntryClaimsItOnce() {
        ReceivablePayment payment = withRounding("0.02");

        assertThat(payment.claimCashRounding(new BigDecimal("9.98"))).isEqualByComparingTo("0.02");
        assertThat(payment.isCashRoundingPosted()).isTrue();
        assertThat(payment.claimCashRounding(new BigDecimal("9.98"))).isNull();
    }

    @Test
    void anEntryTooSmallToAbsorbANegativeRoundingDefersIt() {
        ReceivablePayment payment = withRounding("-0.02");

        assertThat(payment.claimCashRounding(new BigDecimal("0.01"))).isNull();
        assertThat(payment.isCashRoundingPosted()).isFalse();
        assertThat(payment.claimCashRounding(new BigDecimal("10.01"))).isEqualByComparingTo("-0.02");
        assertThat(payment.isCashRoundingPosted()).isTrue();
    }

    @Test
    void noRoundingNothingToClaim() {
        assertThat(withRounding(null).claimCashRounding(new BigDecimal("9.98"))).isNull();
        assertThat(withRounding("0.00").claimCashRounding(new BigDecimal("9.98"))).isNull();
    }
}
