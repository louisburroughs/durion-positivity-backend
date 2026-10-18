package com.positivity.accounting.internal.billintake.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Where the bytes of bill files live (CAP:550 S25, #2518; ADR-0070 Decision 8). Every file belongs to the bound tenant;
 * ids are opaque. {@link DbFileStore} keeps them in Postgres ({@code bill_source_file_content}); a later store can sit
 * behind this port without a caller changing. Nothing here logs bytes or names (ADR-0072).
 */
public interface FileStore {

    /** A stored file's metadata. */
    record StoredFile(
            @NonNull UUID fileId, @NonNull String sha256, long sizeBytes, @NonNull String contentType) {}

    /** A stored file's metadata and bytes. */
    record StoredContent(
            @NonNull UUID fileId,
            @NonNull String sha256,
            @NonNull String contentType,
            long sizeBytes,
            byte @NonNull [] bytes) {}

    /** Stores the bytes apart, {@code QUARANTINED}: not yet scanned or inspected, never served. */
    @NonNull
    StoredFile quarantine(
            byte @NonNull [] bytes,
            @NonNull String contentType,
            @Nullable String originalName,
            @NonNull String actor);

    /**
     * Records a file refused before it was ever stored (a type outside the allow-list): metadata and sha-256 only,
     * state {@code REJECTED}, no bytes kept.
     */
    @NonNull
    StoredFile recordRejected(
            byte @NonNull [] bytes,
            @NonNull String contentType,
            @Nullable String originalName,
            @NonNull String actor,
            @NonNull Instant retentionUntil);

    /** Scanned clean and inspected: {@code QUARANTINED} to {@code STORED}. */
    void promote(@NonNull UUID fileId, @Nullable Integer pageCount);

    /** Refused after it was stored (malware, scan failure, inspection): {@code REJECTED}, bytes kept until retention. */
    void reject(@NonNull UUID fileId, @NonNull Instant retentionUntil);

    /** The bytes of a file that still has them (not {@code PURGED}), whatever its state; empty otherwise. */
    @NonNull
    Optional<StoredContent> open(@NonNull UUID fileId);

    /** Stores a page rendered from {@code derivedFrom}; {@code STORED} at once. */
    @NonNull
    UUID storeDerived(
            @NonNull UUID derivedFrom,
            int pageNumber,
            @NonNull String contentType,
            byte @NonNull [] bytes,
            @NonNull String actor);

    /** Sets when the file may be purged; null clears it. */
    void setRetentionUntil(@NonNull UUID fileId, @Nullable Instant retentionUntil);

    /** Sets or releases the legal hold. */
    void setLegalHold(@NonNull UUID fileId, boolean onHold, @Nullable String reason);

    /** Drops the bytes of the file and its derived pages and marks them {@code PURGED}; the metadata stays. */
    void purge(@NonNull UUID fileId);
}
