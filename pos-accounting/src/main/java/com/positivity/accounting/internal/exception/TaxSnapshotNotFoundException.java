package com.positivity.accounting.internal.exception;

import java.util.UUID;

/**
 * Thrown when a Sales-Tax Liability snapshot cannot be found by id (issue #998). Maps to 404
 * TAX_SNAPSHOT_NOT_FOUND.
 */
public class TaxSnapshotNotFoundException extends RuntimeException {

    private final UUID snapshotId;

    public TaxSnapshotNotFoundException(UUID snapshotId) {
        super("Tax liability snapshot not found: " + snapshotId);
        this.snapshotId = snapshotId;
    }

    public UUID getSnapshotId() {
        return snapshotId;
    }
}
