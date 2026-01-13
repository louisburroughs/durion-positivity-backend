package com.positivity.accounting.audit.entity;

/**
 * Intent for accounting treatment.
 */
public enum AccountingIntent {
    /**
     * Revenue adjustment for price override.
     */
    REVENUE_ADJUSTMENT,
    
    /**
     * Payment reversal for refund.
     */
    PAYMENT_REVERSAL,
    
    /**
     * Customer credit for refund.
     */
    CUSTOMER_CREDIT,
    
    /**
     * Write-off for adjustment.
     */
    WRITE_OFF,
    
    /**
     * Revenue reversal for cancellation.
     */
    REVENUE_REVERSAL,
    
    /**
     * Payment recovery for cancellation.
     */
    PAYMENT_RECOVERY
}
