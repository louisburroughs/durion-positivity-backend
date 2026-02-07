package com.positivity.order.internal.entity;

/**
 * Status values for price override lifecycle.
 */
public enum OverrideStatus {
    /**
     * Override has been requested and is pending approval.
     */
    PENDING_APPROVAL,

    /**
     * Override has been approved by authorized manager.
     */
    APPROVED,

    /**
     * Override has been rejected.
     */
    REJECTED,

    /**
     * Override has been applied to the order line.
     */
    APPLIED,

    /**
     * Override has been cancelled or reverted.
     */
    CANCELLED
}
