package com.positivity.order.security;

/**
 * Permission constants for price override operations.
 */
public final class PriceOverridePermissions {
    
    /**
     * Permission to apply/request a price override.
     */
    public static final String PRICE_OVERRIDE_APPLY = "PRICE_OVERRIDE_APPLY";
    
    /**
     * Permission to approve price overrides.
     */
    public static final String PRICE_OVERRIDE_APPROVE = "PRICE_OVERRIDE_APPROVE";
    
    /**
     * Permission to view price override history and reports.
     */
    public static final String PRICE_OVERRIDE_VIEW = "PRICE_OVERRIDE_VIEW";
    
    /**
     * Permission to cancel/reject price overrides.
     */
    public static final String PRICE_OVERRIDE_REJECT = "PRICE_OVERRIDE_REJECT";
    
    private PriceOverridePermissions() {
        // Utility class
    }
}
