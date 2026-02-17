package com.positivity.inventory.internal.enums;

/**
 * Reason codes for putaway operation overrides.
 * Used for audit tracking when business rules are bypassed with proper
 * authorization.
 */
public enum OverrideReasonCode {
    /**
     * Location capacity was exceeded due to operational necessity.
     */
    CAPACITY_OVERRIDE,

    /**
     * SKU was placed in incompatible location due to lack of alternatives.
     */
    LOCATION_COMPATIBILITY_OVERRIDE,

    /**
     * Temporary placement for urgent fulfillment needs.
     */
    URGENT_FULFILLMENT,

    /**
     * Standard location unavailable, using alternate approved location.
     */
    ALTERNATE_LOCATION,

    /**
     * Other reason with detailed justification required.
     */
    OTHER
}
