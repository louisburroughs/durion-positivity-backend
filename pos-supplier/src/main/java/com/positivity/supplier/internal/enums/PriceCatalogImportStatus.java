package com.positivity.supplier.internal.enums;

/**
 * Lifecycle of one PRICAT import run (ADR-0053 §7).
 *
 * <p>A run reaches exactly one terminal state, and only {@link #COMPLETED} publishes the
 * completion event that lets a consumer declare its own application complete. {@link #EMPTY} and
 * {@link #FAILED} are terminal too, and neither of them removes or supersedes anything: a vendor
 * that answers with no lines, or does not answer at all, must never destroy the catalog the last
 * good fetch produced.
 */
public enum PriceCatalogImportStatus {
    /** Fetch or staging is in flight; no completion event has been published. */
    IN_PROGRESS,

    /** The vendor answered with at least one line and staging committed. */
    COMPLETED,

    /** The vendor answered with zero lines; prior entries are retained untouched. */
    EMPTY,

    /** The exchange or decode failed; prior entries are retained untouched. */
    FAILED
}
