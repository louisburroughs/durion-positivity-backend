package com.positivity.accounting.internal.audit.entity;

/**
 * Method of refund execution.
 */
public enum RefundMethod {
    /**
     * Void the transaction (unsettled).
     */
    VOID,
    
    /**
     * Chargeback the transaction (unsettled).
     */
    CHARGEBACK,
    
    /**
     * Cash refund to customer (settled).
     */
    CASH_REFUND,
    
    /**
     * Issue credit memo (settled).
     */
    CREDIT_MEMO
}
