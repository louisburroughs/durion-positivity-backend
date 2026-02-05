package com.positivity.customer.internal.dto.snapshot;

import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot metadata for CRM snapshot response.
 * CAP:092 - Story #99
 */
public class SnapshotMetadata {
    @NonNull
    private UUID snapshotId;
    @NonNull
    private Instant createdAt;
    @NonNull
    private String version;

    public SnapshotMetadata() {
    }

    public SnapshotMetadata(@NonNull UUID snapshotId, @NonNull Instant createdAt, @NonNull String version) {
        this.snapshotId = snapshotId;
        this.createdAt = createdAt;
        this.version = version;
    }

    @NonNull
    public UUID getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(@NonNull UUID snapshotId) {
        this.snapshotId = snapshotId;
    }

    @NonNull
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(@NonNull Instant createdAt) {
        this.createdAt = createdAt;
    }

    @NonNull
    public String getVersion() {
        return version;
    }

    public void setVersion(@NonNull String version) {
        this.version = version;
    }
}
