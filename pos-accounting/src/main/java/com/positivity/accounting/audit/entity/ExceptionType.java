package com.positivity.accounting.audit.entity;

/**
 * Types of financial exceptions tracked in the audit trail.
 */
public enum ExceptionType {
    /**
     * Price override on an order line item.
     */
    PRICE_OVERRIDE,
    
    /**
     * Refund or payment reversal.
     */
    REFUND,
    
    /**
     * Order or invoice cancellation.
     */
    CANCELLATION
}
