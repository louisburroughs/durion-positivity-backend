package com.positivity.inventory.internal.enums;

/**
 * Status lifecycle for inventory adjustments from cycle counts.
 * 
 * <p>
 * State transitions:
 * <ul>
 * <li>PENDING_APPROVAL → APPROVED → POSTED</li>
 * <li>PENDING_APPROVAL → REJECTED</li>
 * <li>AUTO_APPROVED → POSTED (for below-threshold adjustments)</li>
 * <li>Any state → FAILED (on error during processing)</li>
 * </ul>
 */
public enum AdjustmentStatus {
    /**
     * Adjustment awaiting manual approval from an authorized user.
     * This status is assigned when the adjustment exceeds approval thresholds.
     */
    PENDING_APPROVAL,

    /**
     * Adjustment was automatically approved because it fell below all thresholds.
     * System proceeds directly to posting with full audit trail.
     */
    AUTO_APPROVED,

    /**
     * Adjustment was manually approved by an authorized user.
     * Ready to be posted to inventory ledger.
     */
    APPROVED,

    /**
     * Adjustment has been posted to the inventory ledger.
     * This is the final successful state. Quantity on-hand has been updated.
     */
    POSTED,

    /**
     * Adjustment was rejected by an authorized user.
     * No changes made to inventory. State is final.
     */
    REJECTED,

    /**
     * Processing failed due to an error (e.g., SKU not found, concurrent
     * modification).
     * Requires manual investigation.
     */
    FAILED
}
