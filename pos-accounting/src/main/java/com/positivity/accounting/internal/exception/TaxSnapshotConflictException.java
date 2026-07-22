package com.positivity.accounting.internal.exception;

import java.util.UUID;

/**
 * Thrown when a Sales-Tax Liability snapshot freeze is requested for a period that already has an
 * ACTIVE snapshot and {@code supersede} was not set (issue #998). Maps to 409
 * TAX_SNAPSHOT_ALREADY_EXISTS.
 *
 * <p>{@code existingSnapshotId} is null when the conflict was detected as a lost insert race
 * (partial unique index violation from a concurrent freeze) — the winner's id is not reliably
 * readable inside the poisoned transaction.
 */
public class TaxSnapshotConflictException extends RuntimeException {

    private final UUID existingSnapshotId;

    public TaxSnapshotConflictException(UUID existingSnapshotId, String message) {
        super(message);
        this.existingSnapshotId = existingSnapshotId;
    }

    public TaxSnapshotConflictException(UUID existingSnapshotId, String message, Throwable cause) {
        super(message, cause);
        this.existingSnapshotId = existingSnapshotId;
    }

    public UUID getExistingSnapshotId() {
        return existingSnapshotId;
    }
}
