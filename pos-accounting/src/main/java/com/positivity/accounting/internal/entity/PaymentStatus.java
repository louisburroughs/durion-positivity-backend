package com.positivity.accounting.internal.entity;

/**
 * Enumeration of possible payment statuses for an invoice.
 */
public enum PaymentStatus {
    /**
     * Invoice has been fully paid
     */
    PAID,

    /**
     * Invoice has been partially paid
     */
    PARTIALLY_PAID,

    /**
     * Invoice has not been paid
     */
    UNPAID,

    /**
     * Payment attempt failed
     */
    FAILED
}
