package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotResponse;
import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotSummary;
import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotVerification;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Period-close-aligned, auditable freeze of the Sales-Tax Liability report (issue #998, Phase-2
 * scope item 2; report itself is parity-T8, issue #966).
 *
 * <p>A freeze captures the T8 report over a CLOSED accounting period's exact date range as an
 * immutable snapshot with a canonical SHA-256 content hash. The snapshot is re-derivable: verify
 * regenerates the report from the live replicas and compares hashes, so any post-freeze data
 * movement (e.g. postings into a reopened period) is detectable. At most one ACTIVE snapshot
 * exists per period; re-freezing with {@code supersede} moves the prior one to SUPERSEDED,
 * preserving history. Provider-neutral by design per the #998 decision record.
 */
public interface TaxLiabilitySnapshotService {

    /**
     * Freeze the Sales-Tax Liability report for a CLOSED accounting period.
     *
     * @param periodCode period code in {@code YYYY-MM} format; the period must exist and be CLOSED
     * @param supersede  when an ACTIVE snapshot already exists for the period: {@code true}
     *                   supersedes it and freezes anew (reopen → adjust → re-close flow),
     *                   {@code false} fails with a conflict
     * @return the newly frozen ACTIVE snapshot, including rows and content hash
     */
    @NonNull
    TaxLiabilitySnapshotResponse freeze(@NonNull String periodCode, boolean supersede);

    /** Full snapshot (rows included) by id. */
    @NonNull
    TaxLiabilitySnapshotResponse get(@NonNull UUID snapshotId);

    /**
     * Snapshot summaries, newest freeze first; filtered to one period when {@code periodCode} is
     * non-null.
     */
    @NonNull
    List<TaxLiabilitySnapshotSummary> list(@Nullable String periodCode);

    /**
     * Re-derive the report for the snapshot's period from live data and compare canonical hashes.
     * Read-only: never mutates the snapshot.
     */
    @NonNull
    TaxLiabilitySnapshotVerification verify(@NonNull UUID snapshotId);
}
