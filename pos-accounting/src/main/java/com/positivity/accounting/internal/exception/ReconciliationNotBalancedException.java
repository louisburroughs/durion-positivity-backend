package com.positivity.accounting.internal.exception;

import java.math.BigDecimal;

/**
 * Thrown when a bank reconciliation is finalized while it does not balance — the
 * statement ending balance does not equal (GL ending balance as-of the statement
 * date + Σ adjustments) within ±0.01 (Story F2, issue #965). Maps to 422
 * RECONCILIATION_NOT_BALANCED and surfaces the outstanding {@link #getDifference()
 * difference}.
 */
public class ReconciliationNotBalancedException extends RuntimeException {

    private final transient BigDecimal difference;

    public ReconciliationNotBalancedException(String message, BigDecimal difference) {
        super(message);
        this.difference = difference;
    }

    public BigDecimal getDifference() {
        return difference;
    }
}
