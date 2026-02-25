package com.positivity.inventory.internal.enums;

/**
 * Stock movement types for inventory ledger recording.
 *
 * Issue: CAP-215 Story #37
 */
public enum MovementType {
    /** Goods received from supplier. */
    RECEIVE,
    /** Stock moved to storage location (put-away). */
    PUT_AWAY,
    /** Stock picked from storage location. */
    PICK,
    /** Stock issued/consumed. */
    ISSUE,
    /** Stock returned to inventory. */
    RETURN,
    /** Stock transferred between locations. */
    TRANSFER,
    /**
     * Manual stock adjustment (requires approval workflow).
     *
     * <p>
     * Not valid for direct /v1/inventory/stock-movements requests. Use
     * /v1/inventory/adjustments create + approve endpoints instead.
     */
    ADJUST
}
