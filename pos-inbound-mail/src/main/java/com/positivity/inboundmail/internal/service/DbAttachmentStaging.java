package com.positivity.inboundmail.internal.service;

import com.positivity.inboundmail.internal.config.InboundMailProperties;
import com.positivity.inboundmail.internal.dto.StagedAttachmentBytes;
import com.positivity.inboundmail.internal.dto.StagedAttachmentRef;
import com.positivity.inboundmail.internal.entity.StagedAttachment;
import com.positivity.inboundmail.internal.entity.StagedAttachmentContent;
import com.positivity.inboundmail.internal.repository.StagedAttachmentContentRepository;
import com.positivity.inboundmail.internal.repository.StagedAttachmentRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres adapter of {@link AttachmentStaging}. Metadata and bytes are two row-level-secured tables, so
 * the database itself answers "no such row" for another tenant's ref. Nothing here parses the bytes, and
 * nothing logs a file name, a size-bearing path or a byte: only the ref and counts (ADR-0072).
 */
@Slf4j
@Service
@Transactional
public class DbAttachmentStaging implements AttachmentStaging {

    private final StagedAttachmentRepository attachments;
    private final StagedAttachmentContentRepository contents;
    private final InboundMailProperties properties;
    private final Clock clock;

    public DbAttachmentStaging(
            StagedAttachmentRepository attachments,
            StagedAttachmentContentRepository contents,
            InboundMailProperties properties,
            Clock clock) {
        this.attachments = attachments;
        this.contents = contents;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public @NonNull StagedAttachmentRef stage(
            @NonNull UUID inboundMessageId,
            @NonNull String fileName,
            @NonNull String declaredType,
            @NonNull byte[] content) {
        if (content.length > properties.maxAttachmentBytes()) {
            throw new IllegalArgumentException("attachment exceeds pos.inbound-mail.max-attachment-bytes");
        }
        StagedAttachment saved = attachments.saveAndFlush(StagedAttachment.builder()
                .inboundMessageId(inboundMessageId)
                .fileName(fileName)
                .declaredType(declaredType)
                .sizeBytes(content.length)
                .sha256(sha256Hex(content))
                .build());
        contents.save(new StagedAttachmentContent(saved.getRef(), content));
        log.debug("Staged attachment ref={}", saved.getRef());
        return new StagedAttachmentRef(
                saved.getRef(), saved.getDeclaredType(), saved.getSizeBytes(), saved.getSha256());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<StagedAttachmentBytes> fetch(@NonNull UUID ref) {
        return attachments
                .findById(ref)
                .filter(attachment -> attachment.getCollectedAt() == null)
                .flatMap(attachment -> contents.findById(ref)
                        .map(content -> new StagedAttachmentBytes(
                                content.getContent(), attachment.getSizeBytes(), attachment.getSha256())));
    }

    @Override
    public int release(@NonNull UUID inboundMessageId) {
        Instant now = Instant.now(clock);
        List<StagedAttachment> staged = attachments.findByInboundMessageId(inboundMessageId);
        for (StagedAttachment attachment : staged) {
            if (attachment.getCollectedAt() == null) {
                attachment.setCollectedAt(now);
            }
            deleteContent(attachment.getRef());
        }
        return staged.size();
    }

    @Override
    public int purgeStagedBefore(@NonNull Instant cutoff) {
        List<StagedAttachment> expired = attachments.findByCreatedAtBefore(cutoff);
        for (StagedAttachment attachment : expired) {
            deleteContent(attachment.getRef());
            attachments.delete(attachment);
        }
        return expired.size();
    }

    private void deleteContent(UUID ref) {
        if (contents.existsById(ref)) {
            contents.deleteById(ref);
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
