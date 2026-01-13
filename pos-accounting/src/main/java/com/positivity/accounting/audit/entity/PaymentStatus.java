package com.positivity.accounting.audit.entity;

/**
 * Status of a payment for refund processing.
 */
public enum PaymentStatus {
    /**
     * Payment is pending settlement.
     */
    PENDING,
    
    /**
     * Payment has been settled.
     */
    SETTLED
}
