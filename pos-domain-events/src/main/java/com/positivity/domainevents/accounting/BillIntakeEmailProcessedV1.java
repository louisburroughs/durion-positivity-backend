package com.positivity.domainevents.accounting;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact {@code accounting.bill-intake.email-processed} v1 on {@code accounting.events.v1} (CAP:550 S27,
 * #2520, ruling 6): pos-accounting has finished with one {@code accounting.bill-intake.email-received}
 * command. The inbound-mail edge consumes it to free that message's staged attachments, so it is
 * published for every command, rejected ones included.
 *
 * <p>Published by pos-accounting through its transactional outbox in the transaction that settles the
 * email row, keyed by {@link #inboundMessageId}. The tenant is the envelope's. No file name, sender or
 * subject travels here: the outcome of an attachment is named by its {@link Outcome#ref()}.
 *
 * @param commandId the command this settles
 * @param inboundMessageId the edge's record of the message, echoed from the command
 * @param status echoes the command's status: {@code ACCEPTED} or {@code REJECTED}
 * @param reason the rejection reason echoed from the command, or why an accepted message produced no
 *     drafts; absent when every attachment became a draft
 * @param outcomes one entry per attachment the command carried, empty for a rejected command
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BillIntakeEmailProcessedV1(
        @NonNull UUID commandId,
        @NonNull UUID inboundMessageId,
        @NonNull Status status,
        @Nullable String reason,
        @NonNull List<Outcome> outcomes) {

    public static final String EVENT_TYPE = "accounting.bill-intake.email-processed";
    public static final int SCHEMA_VERSION = 1;

    /** Echo of the command's status. */
    public enum Status {
        ACCEPTED,
        REJECTED
    }

    /** What became of one attachment. */
    public enum OutcomeKind {
        /** A draft was created (or, on a redelivery, found); {@link Outcome#intakeId()} names it. */
        DRAFT_CREATED,
        /** The fetched bytes differed from the command's size or sha256; no draft. */
        ATTACHMENT_MISMATCH,
        /** The edge no longer had the attachment (purged after the staging TTL); no draft. */
        ATTACHMENT_UNAVAILABLE
    }

    /**
     * @param ref the staged attachment's ref from the command
     * @param outcome what became of it
     * @param intakeId the draft's intake id; required for {@code DRAFT_CREATED}, absent otherwise
     */
    public record Outcome(@NonNull UUID ref, @NonNull OutcomeKind outcome, @Nullable UUID intakeId) {

        public Outcome {
            if (ref == null) {
                throw new IllegalArgumentException("ref must not be null");
            }
            if (outcome == null) {
                throw new IllegalArgumentException("outcome must not be null");
            }
            if (outcome == OutcomeKind.DRAFT_CREATED && intakeId == null) {
                throw new IllegalArgumentException("intakeId is required for DRAFT_CREATED");
            }
        }
    }

    public BillIntakeEmailProcessedV1 {
        if (commandId == null || inboundMessageId == null) {
            throw new IllegalArgumentException("commandId and inboundMessageId must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }
}
