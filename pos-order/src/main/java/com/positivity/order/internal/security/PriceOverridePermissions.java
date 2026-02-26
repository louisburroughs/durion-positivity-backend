package com.positivity.order.internal.security;

/**
 * Permission constants for price override operations.
 */
public final class PriceOverridePermissions {
    
    /**
     * Permission to apply/request a price override.
     */
    public static final String PRICE_OVERRIDE_APPLY = "order:price_override:apply";
    
    /**
     * Permission to approve price overrides.
     */
    public static final String PRICE_OVERRIDE_APPROVE = "order:price_override:approve";
    
    /**
     * Permission to view price override history and reports.
     */
    public static final String PRICE_OVERRIDE_VIEW = "order:price_override:view";
    
    /**
     * Permission to cancel/reject price overrides.
     */
    public static final String PRICE_OVERRIDE_REJECT = "order:price_override:reject";
    
    private PriceOverridePermissions() {
        // Utility class
    }
}
