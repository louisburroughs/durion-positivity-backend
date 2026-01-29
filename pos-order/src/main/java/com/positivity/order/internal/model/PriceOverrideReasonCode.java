package com.positivity.order.internal.model;

/**
 * Reason codes for price override operations.
 * Used for audit tracking and compliance reporting.
 */
public enum PriceOverrideReasonCode {
    /**
     * Customer loyalty discount or retention offer.
     */
    CUSTOMER_LOYALTY,
    
    /**
     * Price matching with competitor.
     */
    PRICE_MATCH,
    
    /**
     * Promotional pricing not in system.
     */
    PROMOTIONAL_PRICING,
    
    /**
     * Correction of pricing error.
     */
    PRICING_ERROR_CORRECTION,
    
    /**
     * Volume discount for bulk purchase.
     */
    VOLUME_DISCOUNT,
    
    /**
     * Goodwill adjustment for service recovery.
     */
    GOODWILL_ADJUSTMENT,
    
    /**
     * Manager discretion override.
     */
    MANAGER_DISCRETION,
    
    /**
     * Other reason with detailed justification required.
     */
    OTHER
}
