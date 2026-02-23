package com.positivity.workorder.internal.enums;

/**
 * Approval status for estimate line items.
 * CAP:003 - Capture Customer Approval
 */
public enum ApprovalStatus {
    /**
     * Line item is pending customer approval (default state).
     */
    PENDING_APPROVAL,

    /**
     * Customer approved this line item.
     */
    APPROVED,

    /**
     * Customer declined/rejected this line item.
     */
    DECLINED
}
