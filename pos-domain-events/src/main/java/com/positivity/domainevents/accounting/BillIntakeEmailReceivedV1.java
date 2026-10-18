package com.positivity.domainevents.accounting;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Command {@code accounting.bill-intake.email-received} v1 on {@code accounting.commands.v1} (CAP:550 S27,
 * #2520, ruling 6): the inbound-mail edge tells pos-accounting that an email reached a tenant's intake
 * address and what became of it, so the shop's log hears of every message, rejections included.
 *
 * <p>Published by pos-inbound-mail through its transactional outbox in the transaction that records the
 * message, keyed by {@link #inboundMessageId}. The tenant is the envelope's, never a payload field. The
 * receiving address travels as {@link #receivingAddressId}, the registry's id, not as text: the address is
 * an unguessable capability and must not reach Kafka, a DLQ or an outbox row (ADR-0072 minimisation).
 * pos-accounting compares the id with its own current address to detect a rotation race.
 *
 * <p>CONFIDENTIAL (ADR-0072 Decision 1): {@link #fromAddress}, {@link #subject} and every {@link
 * Attachment#fileName()} / {@link RefusedAttachment#fileName()}. They are never logged at INFO or above,
 * never a metric tag, and left out of {@code toString()} of this record and its parts.
 *
 * <p>A {@code REJECTED} command with {@link #rejectionReason} {@code RATE_LIMITED} is the per-window
 * summary (ruling 5): {@link #rateLimitedCount} says how many messages the window refused, {@link
 * #fromAddress} and {@link #subject} are absent, and {@link #inboundMessageId} is the window's id.
 *
 * @param commandId idempotency key of the command; pos-accounting records it in {@code processed_events}
 * @param inboundMessageId the edge's record of the message (the window id for a rate-limit summary)
 * @param receivingAddressId the registry id of the address the message was sent to
 * @param receivedAt when the transport received the message
 * @param fromAddress the authenticated outer {@code From} address; absent on a rate-limit summary
 * @param subject the subject as text, control, format and separator characters stripped and at most
 *     {@value #MAX_SUBJECT_LENGTH} code points; absent on a rate-limit summary
 * @param status {@code ACCEPTED} (its attachments follow) or {@code REJECTED} ({@link #rejectionReason} says why)
 * @param rejectionReason the edge's reason name for a {@code REJECTED} command, for example {@code
 *     SENDER_NOT_AUTHENTICATED}, {@code NO_ATTACHMENT} or {@code RATE_LIMITED}; required when rejected
 * @param rateLimitedCount messages refused in the window, on a {@code RATE_LIMITED} summary only
 * @param attachments the staged attachments to collect, at most {@value #MAX_ATTACHMENTS}; empty when rejected
 * @param refusedAttachments candidates the edge refused (for example {@code FILE_TOO_LARGE}), each with its reason
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BillIntakeEmailReceivedV1(
        @NonNull UUID commandId,
        @NonNull UUID inboundMessageId,
        @NonNull UUID receivingAddressId,
        @NonNull Instant receivedAt,
        @Nullable String fromAddress,
        @Nullable String subject,
        @NonNull Status status,
        @Nullable String rejectionReason,
        @Nullable Integer rateLimitedCount,
        @NonNull List<Attachment> attachments,
        @NonNull List<RefusedAttachment> refusedAttachments) {

    public static final String EVENT_TYPE = "accounting.bill-intake.email-received";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_SUBJECT_LENGTH = 200;
    public static final int MAX_ATTACHMENTS = 20;

    private static final Pattern SUBJECT_STRIPPED = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

    /** Whether the edge accepted the message's attachments or rejected the message. */
    public enum Status {
        ACCEPTED,
        REJECTED
    }

    /**
     * One staged attachment pos-accounting collects with {@code GET /inbound-mail/v1/staged-attachments/{ref}}.
     *
     * @param ref the staged attachment's UUIDv7; stable across redelivery, so it is the draft's request id
     * @param fileName the file name as text, CONFIDENTIAL
     * @param declaredContentType the content type the sender declared
     * @param sizeBytes the staged size, which the consumer checks the fetched bytes against
     * @param sha256 the staged digest, lowercase hex, which the consumer checks the fetched bytes against
     */
    public record Attachment(
            @NonNull UUID ref,
            @NonNull String fileName,
            @NonNull String declaredContentType,
            long sizeBytes,
            @NonNull String sha256) {

        public Attachment {
            if (ref == null) {
                throw new IllegalArgumentException("ref must not be null");
            }
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("fileName must not be blank");
            }
            if (declaredContentType == null || declaredContentType.isBlank()) {
                throw new IllegalArgumentException("declaredContentType must not be blank");
            }
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
            if (sha256 == null || sha256.isBlank()) {
                throw new IllegalArgumentException("sha256 must not be blank");
            }
        }

        @Override
        public String toString() {
            return "Attachment[ref=" + ref + ", sizeBytes=" + sizeBytes + "]";
        }
    }

    /**
     * A candidate the edge refused before staging.
     *
     * @param fileName the file name as text, CONFIDENTIAL
     * @param reason the edge's reason name, for example {@code FILE_TOO_LARGE}
     */
    public record RefusedAttachment(
            @NonNull String fileName, @NonNull String reason) {

        public RefusedAttachment {
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("fileName must not be blank");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }

        @Override
        public String toString() {
            return "RefusedAttachment[reason=" + reason + "]";
        }
    }

    public BillIntakeEmailReceivedV1 {
        if (commandId == null || inboundMessageId == null || receivingAddressId == null) {
            throw new IllegalArgumentException("commandId, inboundMessageId and receivingAddressId must not be null");
        }
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (status == Status.REJECTED && (rejectionReason == null || rejectionReason.isBlank())) {
            throw new IllegalArgumentException("rejectionReason is required when the message is rejected");
        }
        if (rateLimitedCount != null && rateLimitedCount < 1) {
            throw new IllegalArgumentException("rateLimitedCount must be at least 1 when present");
        }
        subject = cleanSubject(subject);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        refusedAttachments = refusedAttachments == null ? List.of() : List.copyOf(refusedAttachments);
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("a message carries at most " + MAX_ATTACHMENTS + " attachments");
        }
    }

    /**
     * Strips control, format and line/paragraph-separator characters (so a CR/LF or a right-to-left override
     * cannot dress up the subject) and cuts it to {@link #MAX_SUBJECT_LENGTH} code points, never inside a
     * surrogate pair. The producer's duty (ruling 6), enforced here.
     */
    private static @Nullable String cleanSubject(@Nullable String subject) {
        if (subject == null) {
            return null;
        }
        String cleaned = SUBJECT_STRIPPED.matcher(subject).replaceAll("");
        return cleaned.codePointCount(0, cleaned.length()) > MAX_SUBJECT_LENGTH
                ? cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_SUBJECT_LENGTH))
                : cleaned;
    }

    @Override
    public String toString() {
        // fromAddress, subject and file names are CONFIDENTIAL (ADR-0072): a record's default toString would print
        // them.
        return "BillIntakeEmailReceivedV1[commandId=" + commandId + ", inboundMessageId=" + inboundMessageId
                + ", status=" + status + ", rejectionReason=" + rejectionReason + ", attachments="
                + attachments.size() + ", refusedAttachments=" + refusedAttachments.size() + "]";
    }
}
