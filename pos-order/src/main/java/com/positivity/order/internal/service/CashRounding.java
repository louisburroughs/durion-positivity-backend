package com.positivity.order.internal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Rounds a cash amount due to the currency's cash increment at the register (ADR-0067 PC-7 (b), OP-11; #2662).
 * One rule: HALF_UP on {@code amountDue / increment}, then back to the currency's exponent, so for CAD 0.05 the
 * cents 1-2 round down and 3-4 up (a true half never arises at two decimals) and for a 0.10 increment x.x5 rounds
 * up. The increment is never applied to document totals or tax; only the cash that crosses the counter rounds.
 */
final class CashRounding {

    private CashRounding() {}

    /**
     * The outcome of rounding {@code amountDue}.
     *
     * @param amountDue the amount applied to the order and the invoice (the fact's {@code amount})
     * @param cashDue the cash the customer pays (the fact's {@code settledAmount})
     * @param cashRoundingAmount {@code cashDue − amountDue}, signed: positive when the customer paid more
     * @param cashIncrement the increment applied, null when the currency has none
     */
    record Rounded(
            @NonNull BigDecimal amountDue,
            @NonNull BigDecimal cashDue,
            @NonNull BigDecimal cashRoundingAmount,
            @Nullable BigDecimal cashIncrement) {}

    static @NonNull Rounded round(@NonNull BigDecimal amountDue, @Nullable BigDecimal increment, int exponent) {
        BigDecimal due = amountDue.setScale(exponent, RoundingMode.UNNECESSARY);
        if (increment == null) {
            return new Rounded(due, due, BigDecimal.ZERO.setScale(exponent, RoundingMode.UNNECESSARY), null);
        }
        BigDecimal cashDue = due.divide(increment, 0, RoundingMode.HALF_UP)
                .multiply(increment)
                .setScale(exponent, RoundingMode.UNNECESSARY);
        return new Rounded(due, cashDue, cashDue.subtract(due), increment);
    }

    /** Whether {@code amount} is a whole number of {@code increment}s (a partial cash amount must be). */
    static boolean isMultipleOf(@NonNull BigDecimal amount, @Nullable BigDecimal increment) {
        return increment == null || amount.remainder(increment).signum() == 0;
    }
}
