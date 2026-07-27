package com.positivity.inventory.internal.enums;

/**
 * Status lifecycle for scrap/write-off documents (odoo-parity D1, issue #1030).
 *
 * <p>Mirrors the {@link AdjustmentStatus} lifecycle of cycle-count adjustments:
 * <ul>
 * <li>PENDING_APPROVAL → APPROVED → POSTED</li>
 * <li>PENDING_APPROVAL → REJECTED</li>
 * <li>AUTO_APPROVED → POSTED (below-threshold scraps post in the create transaction)</li>
 * <li>Any pre-posting state → FAILED (on error during ledger posting)</li>
 * </ul>
 */
public enum ScrapStatus {

    /** Scrap value exceeds a threshold (or is unknown); awaiting manual approval. */
    PENDING_APPROVAL,

    /** Below all value thresholds; system-approved and posted in the same transaction. */
    AUTO_APPROVED,

    /** Manually approved by an authorized user; ready to post to the ledger. */
    APPROVED,

    /** SCRAP_OUT posted to the inventory ledger. Final successful state. */
    POSTED,

    /** Rejected by an authorized user; stock untouched. Final state. */
    REJECTED,

    /** Ledger posting failed; requires manual investigation. */
    FAILED
}
