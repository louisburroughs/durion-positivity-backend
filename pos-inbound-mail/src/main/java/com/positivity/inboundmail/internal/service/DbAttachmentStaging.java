package com.positivity.inboundmail.internal.service;

import com.positivity.inboundmail.internal.config.InboundMailProperties;
import com.positivity.inboundmail.internal.dto.StagedAttachmentBytes;
import com.positivity.inboundmail.internal.dto.StagedAttachmentRef;
import com.positivity.inboundmail.internal.entity.StagedAttachment;
import com.positivity.inboundmail.internal.entity.StagedAttachmentContent;
import com.positivity.inboundmail.internal.repository.StagedAttachmentContentRepository;
import com.positivity.inboundmail.internal.repository.StagedAttachmentRepository;
import com.positivity.tenancy.TenantResolver;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Postgres adapter of {@link AttachmentStaging}. Metadata and bytes are two row-level-secured tables, so
 * the database itself answers "no such row" for another tenant's ref. Nothing here parses the bytes, and
 * nothing logs a file name or a byte: only the ref and counts (ADR-0072).
 *
 * <p>{@link #release} and {@link #purgeStagedBefore} are bulk SQL, never entity loads: the bytes of an
 * expired or collected attachment are deleted without ever being read, and the purge removes at most
 * {@code pos.inbound-mail.staging-purge-batch-size} rows per transaction, so a backlog never sits in one
 * transaction. The explicit {@code tenant_id} predicates repeat what row-level security enforces on Postgres.
 */
@Slf4j
@Service
public class DbAttachmentStaging implements AttachmentStaging {

    private final StagedAttachmentRepository attachments;
    private final StagedAttachmentContentRepository contents;
    private final InboundMailProperties properties;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final TenantResolver tenantResolver;
    private final TransactionTemplate transaction;

    public DbAttachmentStaging(
            StagedAttachmentRepository attachments,
            StagedAttachmentContentRepository contents,
            InboundMailProperties properties,
            Clock clock,
            JdbcTemplate jdbc,
            TenantResolver tenantResolver,
            PlatformTransactionManager transactionManager) {
        this.attachments = attachments;
        this.contents = contents;
        this.properties = properties;
        this.clock = clock;
        this.jdbc = jdbc;
        this.tenantResolver = tenantResolver;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    @Transactional
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
        return new StagedAttachmentRef(saved.getRef(), saved.getDeclaredType(), saved.getSizeBytes(), saved.getSha256());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<StagedAttachmentBytes> fetch(@NonNull UUID ref) {
        return attachments
                .findById(ref)
                .filter(attachment -> attachment.getCollectedAt() == null)
                .flatMap(attachment -> contents
                        .findById(ref)
                        .map(content -> new StagedAttachmentBytes(
                                content.getContent(), attachment.getSizeBytes(), attachment.getSha256())));
    }

    @Override
    public int release(@NonNull UUID inboundMessageId) {
        UUID tenantId = tenantResolver.require();
        Timestamp now = Timestamp.from(Instant.now(clock));
        Integer released = transaction.execute(status -> {
            jdbc.update(
                    "UPDATE staged_attachment SET collected_at = ?"
                            + " WHERE tenant_id = ? AND inbound_message_id = ? AND collected_at IS NULL",
                    now,
                    tenantId,
                    inboundMessageId);
            return jdbc.update(
                    "DELETE FROM staged_attachment_content WHERE tenant_id = ? AND ref IN"
                            + " (SELECT ref FROM staged_attachment WHERE tenant_id = ? AND inbound_message_id = ?)",
                    tenantId,
                    tenantId,
                    inboundMessageId);
        });
        return released == null ? 0 : released;
    }

    @Override
    public int purgeStagedBefore(@NonNull Instant cutoff) {
        UUID tenantId = tenantResolver.require();
        Timestamp before = Timestamp.from(cutoff);
        int batch = properties.stagingPurgeBatchSize();
        int total = 0;
        int deleted;
        do {
            // The bytes go with the metadata row through the foreign key's ON DELETE CASCADE.
            Integer n = transaction.execute(status -> jdbc.update(
                    "DELETE FROM staged_attachment WHERE tenant_id = ? AND ref IN"
                            + " (SELECT ref FROM staged_attachment WHERE tenant_id = ? AND created_at < ?"
                            + " ORDER BY created_at LIMIT ?)",
                    tenantId,
                    tenantId,
                    before,
                    batch));
            deleted = n == null ? 0 : n;
            total += deleted;
        } while (deleted > 0);
        return total;
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
