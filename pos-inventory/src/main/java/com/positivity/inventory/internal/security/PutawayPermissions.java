package com.positivity.inventory.internal.security;

/**
 * Permission constants for putaway operations.
 * 
 * <p>As defined in clarification #229 resolution for issue #31.
 */
public final class PutawayPermissions {
    
    /**
     * Permission to override location compatibility rules.
     * Required when a location is not valid for a SKU but business needs to proceed.
     * 
     * <p>Requires: mandatory reason code and free-text justification.
     * Emits audit event: PutawayOverrideLocationRule
     */
    public static final String OVERRIDE_LOCATION_COMPATIBILITY = "OVERRIDE_LOCATION_COMPATIBILITY";
    
    /**
     * Permission to override location capacity limits.
     * Required when attempting to put away to a location at or near full capacity.
     * 
     * <p>Conditions:
     * - Overfill must be within configured tolerance (e.g., ≤ 5-10%)
     * - Requires justification
     * 
     * <p>Audit requirements:
     * - previousCapacity
     * - newCapacity
     * - overrideReasonCode = CAPACITY_OVERRIDE
     * - approvedBy
     */
    public static final String OVERRIDE_LOCATION_CAPACITY = "OVERRIDE_LOCATION_CAPACITY";
    
    /**
     * Permission to initiate a cycle count for reconciliation.
     * Required when source location shows zero on-hand but physical inventory exists.
     * 
     * <p>Creates a reconciliation task for the source location.
     */
    public static final String INITIATE_CYCLE_COUNT = "INITIATE_CYCLE_COUNT";
    
    /**
     * Permission to make inventory adjustments.
     * Required for exceptional reconciliation when on-hand does not match physical reality.
     * 
     * <p>Requires:
     * - Explicit reason code (MISPLACED_STOCK, UNRECORDED_RECEIPT, etc.)
     * - Manager approval if above threshold
     * - Adjustment must complete BEFORE putaway proceeds
     */
    public static final String ADJUST_INVENTORY = "ADJUST_INVENTORY";
    
    private PutawayPermissions() {
        // Utility class - prevent instantiation
    }
}
