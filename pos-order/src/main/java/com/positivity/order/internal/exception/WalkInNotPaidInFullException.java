package com.positivity.order.internal.exception;

import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A walk-in sale must be paid in full now (CAP:550 S8, decision AW12): the cash and card the
 * cashier declared at checkout is absent or below the order's final grand total, computed by the
 * server after the last reprice and tax. Maps to {@code 422 ORDER_WALK_IN_NOT_PAID_IN_FULL} with
 * the grand total named in {@code fieldErrors[tenderedAmount]}.
 */
public class WalkInNotPaidInFullException extends RuntimeException {

    private final BigDecimal grandTotal;
    private final @Nullable BigDecimal tenderedAmount;

    public WalkInNotPaidInFullException(@NonNull BigDecimal grandTotal, @Nullable BigDecimal tenderedAmount) {
        super("A walk-in sale must be paid in full now: the total is " + display(grandTotal)
                + (tenderedAmount == null
                        ? " and no tendered amount was given"
                        : " and " + display(tenderedAmount) + " was tendered"));
        this.grandTotal = grandTotal;
        this.tenderedAmount = tenderedAmount;
    }

    public @NonNull BigDecimal getGrandTotal() {
        return grandTotal;
    }

    public @Nullable BigDecimal getTenderedAmount() {
        return tenderedAmount;
    }

    /** The grand total as the cashier reads it: at least two decimals, no padding beyond them. */
    public @NonNull String grandTotalDisplay() {
        return display(grandTotal);
    }

    private static String display(BigDecimal amount) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return (stripped.scale() < 2 ? stripped.setScale(2) : stripped).toPlainString();
    }
}
