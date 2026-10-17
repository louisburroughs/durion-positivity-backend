package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.exception.CashRoundingInvalidException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * ADR-0067 A8 (#2662, Invoicing & Payments ruling D3): the rounding triple a register sends with a cash tender has
 * to be internally consistent before pos-invoice records it. The invoice amount keeps its meaning; {@code
 * settledAmount} is the cash that changed hands and {@code cashRoundingAmount} the signed difference. The check
 * recomputes the register's HALF_UP rounding rather than trusting it.
 */
final class CashRoundingCheck {

    private CashRoundingCheck() {}

    static void verify(
            @NonNull BigDecimal amount,
            @NonNull BigDecimal settledAmount,
            @NonNull BigDecimal cashRoundingAmount,
            @Nullable BigDecimal cashIncrement) {
        if (cashIncrement == null) {
            if (settledAmount.compareTo(amount) != 0) {
                throw new CashRoundingInvalidException(
                        "settledAmount must equal amount when no cashIncrement applies to the currency");
            }
            if (cashRoundingAmount.signum() != 0) {
                throw new CashRoundingInvalidException(
                        "cashRoundingAmount must be 0 when no cashIncrement applies to the currency");
            }
            return;
        }
        if (cashIncrement.signum() <= 0) {
            throw new CashRoundingInvalidException("cashIncrement must be greater than zero");
        }
        if (settledAmount.remainder(cashIncrement).signum() != 0) {
            throw new CashRoundingInvalidException("settledAmount " + settledAmount
                    + " is not a multiple of cashIncrement " + cashIncrement);
        }
        if (settledAmount.subtract(amount).compareTo(cashRoundingAmount) != 0) {
            throw new CashRoundingInvalidException(
                    "cashRoundingAmount must equal settledAmount minus amount (" + settledAmount.subtract(amount) + ")");
        }
        BigDecimal halfIncrement = cashIncrement.divide(BigDecimal.valueOf(2));
        if (cashRoundingAmount.abs().compareTo(halfIncrement) > 0) {
            throw new CashRoundingInvalidException(
                    "cashRoundingAmount " + cashRoundingAmount + " exceeds half of cashIncrement " + cashIncrement);
        }
        BigDecimal expected = amount.divide(cashIncrement, 0, RoundingMode.HALF_UP).multiply(cashIncrement);
        if (expected.compareTo(settledAmount) != 0) {
            throw new CashRoundingInvalidException("settledAmount " + settledAmount + " is not amount " + amount
                    + " rounded HALF_UP to the nearest " + cashIncrement + " (expected " + expected + ")");
        }
    }
}
