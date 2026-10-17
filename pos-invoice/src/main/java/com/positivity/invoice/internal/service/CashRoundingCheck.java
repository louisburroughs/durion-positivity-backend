package com.positivity.invoice.internal.service;

import com.positivity.domainevents.payment.CashRoundingTriple;
import com.positivity.invoice.internal.exception.CashRoundingInvalidException;
import java.math.BigDecimal;
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
        // The rule lives beside the fact (pos-domain-events CashRoundingTriple) so the publisher and the consumers
        // cannot drift apart.
        CashRoundingTriple.problem(amount, settledAmount, cashRoundingAmount, cashIncrement)
                .ifPresent(problem -> {
                    throw new CashRoundingInvalidException(problem);
                });
    }
}
