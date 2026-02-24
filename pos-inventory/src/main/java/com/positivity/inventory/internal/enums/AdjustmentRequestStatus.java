package com.positivity.inventory.internal.enums;

/**
 * Status of an inventory adjustment request.
 *
 * Issue: CAP-215 Story #37
 */
public enum AdjustmentRequestStatus {
    /** Created, awaiting approval. */
    PENDING,
    /** Approved and posted to the ledger. */
    APPROVED,
    /** Rejected and not posted. */
    REJECTED
}
