package com.positivity.accounting.internal.enums;

/**
 * Settlement status of the original payment for refund processing.
 * Determines which refund method is applicable (e.g., void vs. credit memo).
 */
public enum RefundPaymentStatus {
    /**
     * Payment is pending settlement.
     */
    PENDING,

    /**
     * Payment has been settled.
     */
    SETTLED,
    /**
     * Payment settlement failed.
     */
    FAILED,
    /**
     * Payment has been authorized but not yet settled.
     */
    AUTHORIZED
}
