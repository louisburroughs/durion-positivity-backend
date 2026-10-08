package com.positivity.tax.common.enums;

/**
 * Lifecycle status of a provider tax document (story T6, decision D-T3).
 * <p>
 * A calculation is <em>estimated</em> (uncommitted) at pricing time; on invoice
 * finalization it is {@link #COMMITTED}. When the provider is unavailable at
 * finalization the platform does <strong>not</strong> block the sale — it records a
 * {@link #PENDING_COMMIT} row and a scheduled re-commit job promotes it to
 * {@link #COMMITTED} when the provider recovers (estimate-and-true-up). A document is
 * {@link #VOIDED} when its invoice reverts to DRAFT. {@link #FAILED} marks a void that
 * could not be applied at the provider. {@link #ESTIMATED} is a log row written at pricing time,
 * before any commit, only so that commit and void reach the plug-in that priced the document
 * (ADR-0071 §3; CAP:550 S32a); commit and void never return it.
 */
public enum TaxProviderTransactionStatus {
    /** Commit could not be applied yet; awaiting the scheduled re-commit job (D-T3). */
    PENDING_COMMIT,
    /** Provider document committed (or test-mode no-op recorded). */
    COMMITTED,
    /** Provider document voided (invoice reverted to DRAFT). */
    VOIDED,
    /** A void could not be applied at the provider. */
    FAILED,
    /**
     * Priced by a per-country plug-in and not yet committed: recorded at pricing time so commit
     * and void reach the plug-in that priced the document. Never picked up by the re-commit job.
     */
    ESTIMATED
}
