package com.positivity.customer.internal.dto.snapshot;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
    /** Data source: CACHE or CRM_API. Null if not set. */
    @Nullable
    private String source;

    /** True if this snapshot was served from a stale (expired) cache entry. */
    private boolean stale;

    /** When the cache entry went stale; only present when stale=true. */
    @Nullable
    private Instant staleSince;

    public SnapshotMetadata() {}

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

    @Nullable
    public String getSource() {
        return source;
    }

    public void setSource(@Nullable String source) {
        this.source = source;
    }

    public boolean isStale() {
        return stale;
    }

    public void setStale(boolean stale) {
        this.stale = stale;
    }

    @Nullable
    public Instant getStaleSince() {
        return staleSince;
    }

    public void setStaleSince(@Nullable Instant staleSince) {
        this.staleSince = staleSince;
    }
}
