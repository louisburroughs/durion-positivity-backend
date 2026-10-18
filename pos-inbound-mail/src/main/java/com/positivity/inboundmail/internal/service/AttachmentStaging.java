package com.positivity.inboundmail.internal.service;

import com.positivity.inboundmail.internal.dto.StagedAttachmentBytes;
import com.positivity.inboundmail.internal.dto.StagedAttachmentRef;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Where an accepted attachment waits until pos-accounting collects it (S27 ruling 3). Every method works
 * on the bound tenant's rows only. The v1 adapter keeps the bytes in the edge's own Postgres under
 * row-level security; an object-store adapter can replace it without changing this contract.
 */
public interface AttachmentStaging {

    /** Stores one attachment of {@code inboundMessageId} and returns the ref the command carries. */
    @NonNull
    StagedAttachmentRef stage(
            @NonNull UUID inboundMessageId,
            @NonNull String fileName,
            @NonNull String declaredType,
            @NonNull byte[] content);

    /**
     * The attachment's bytes, or empty when the ref is unknown, belongs to another tenant, was collected
     * or was purged. The four cases are deliberately indistinguishable.
     */
    @NonNull
    Optional<StagedAttachmentBytes> fetch(@NonNull UUID ref);

    /** Marks every attachment of the message collected and deletes its bytes; returns how many were released. */
    int release(@NonNull UUID inboundMessageId);

    /** Deletes every attachment (and its bytes) staged before {@code cutoff}; returns how many were removed. */
    int purgeStagedBefore(@NonNull Instant cutoff);
}
