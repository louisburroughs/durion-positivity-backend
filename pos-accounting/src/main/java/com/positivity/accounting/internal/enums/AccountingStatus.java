package com.positivity.accounting.internal.enums;

/**
 * v1 accounting status values for invoice GL lifecycle.
 *
 * <p>
 * These values represent the authoritative accounting state of an invoice
 * as reported by the accounting subsystem. Used by
 * {@code AccountingStatusSyncService}
 * to reconcile POS invoice state with accounting authoritative state.
 * </p>
 *
 * Issue: CAP-251 #5
 */
public enum AccountingStatus {
    /** Pending posting to GL. */
    PENDING_POSTING,

    /** Successfully posted to GL. */
    POSTED,

    /** Posted and reconciled against bank/statement. */
    RECONCILED,

    /** Rejected by accounting (validation or rule failure). */
    REJECTED,

    /** Entry reversed in GL. */
    REVERSED,

    /** Invoice voided, no GL activity expected. */
    VOIDED,

    /** Posting on hold pending manual review. */
    ON_HOLD,

    /** Entry disputed; under investigation. */
    DISPUTED
}
