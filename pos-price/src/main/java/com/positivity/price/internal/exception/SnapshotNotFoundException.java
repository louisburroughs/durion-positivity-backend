package com.positivity.price.internal.exception;

import java.util.UUID;

/**
 * Thrown when a pricing snapshot cannot be found.
 *
 * Issue: #50
 */
public class SnapshotNotFoundException extends RuntimeException {

    public SnapshotNotFoundException(UUID snapshotId) {
        super("Pricing snapshot not found: " + snapshotId);
    }
}
