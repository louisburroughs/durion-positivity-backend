package com.positivity.accounting.internal.enums;

/**
 * Lifecycle of a frozen Sales-Tax Liability snapshot (issue #998, Phase-2 scope item 2).
 *
 * <p>Snapshot content is immutable; this status is the only mutable lifecycle metadata. A period
 * has at most one {@code ACTIVE} snapshot (partial unique index). Re-freezing a period after a
 * reopen/re-close moves the prior snapshot to {@code SUPERSEDED}, preserving the full audit
 * history.
 */
public enum TaxLiabilitySnapshotStatus {
    ACTIVE,
    SUPERSEDED
}
