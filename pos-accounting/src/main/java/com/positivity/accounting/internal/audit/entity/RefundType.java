package com.positivity.accounting.internal.audit.entity;

/**
 * Type of refund action.
 */
public enum RefundType {
    /**
     * Payment reversal (void/chargeback) for unsettled payments.
     */
    REVERSAL,
    
    /**
     * Credit memo for settled payments.
     */
    CREDIT_MEMO,
    
    /**
     * Manual adjustment or correction.
     */
    ADJUSTMENT
}
