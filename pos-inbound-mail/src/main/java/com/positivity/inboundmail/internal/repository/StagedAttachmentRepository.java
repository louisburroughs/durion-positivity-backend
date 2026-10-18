package com.positivity.inboundmail.internal.repository;

import com.positivity.inboundmail.internal.entity.StagedAttachment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StagedAttachmentRepository extends JpaRepository<StagedAttachment, UUID> {

    @NonNull
    List<StagedAttachment> findByInboundMessageId(@NonNull UUID inboundMessageId);

    /** Refs of the bound tenant's attachments staged before the cutoff, whether or not they were collected. */
    @NonNull
    List<StagedAttachment> findByCreatedAtBefore(@NonNull Instant cutoff);
}
