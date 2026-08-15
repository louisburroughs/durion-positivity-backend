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
    public static final String OVERRIDE_LOCATION_COMPATIBILITY =
            InventoryPermissionRegistry.PUTAWAY_OVERRIDE_LOCATION_COMPATIBILITY;

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
    public static final String OVERRIDE_LOCATION_CAPACITY =
            InventoryPermissionRegistry.PUTAWAY_OVERRIDE_LOCATION_CAPACITY;

    /**
     * Reconciliation when the source location shows zero on-hand but stock is physically there is
     * not a putaway permission: it is a cycle count or an adjustment, and those are
     * {@code inventory:cycle_count:*} and {@code inventory:adjustment:*} in
     * {@link InventoryPermissionRegistry}.
     *
     * <p>Two constants used to sit here for that, declared as {@code INITIATE_CYCLE_COUNT} and
     * {@code ADJUST_INVENTORY} — bare words rather than {@code domain:resource:action} names. No
     * permission of either name has ever existed in the catalog, so neither could be granted to
     * anyone, and nothing referenced them; they are removed rather than renamed so that a future
     * reader does not mistake them for an authority the system honours.
     */
    private PutawayPermissions() {
        // Utility class - prevent instantiation
    }
}
