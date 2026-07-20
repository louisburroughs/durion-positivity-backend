package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * Outbox work item enqueued when a payment application is reversed (story C2,
 * issue #958), triggering asynchronous posting of a <em>reversing</em> journal
 * entry against the original C1 cash-receipt entry so the GL stays symmetric:
 * <ul>
 * <li>Cr Undeposited Funds (inverse of the original debit)</li>
 * <li>Dr Accounts Receivable (inverse of the original credit)</li>
 * </ul>
 *
 * <p>
 * The reversing entry is not hand-built: the handler locates the original
 * cash-receipt entry by its posting key ({@code applicationRequestId} →
 * {@code sourceEventId}) and delegates to A3's
 * {@link com.positivity.accounting.service.JournalEntryService#reverseJournalEntry}
 * (issue #943), which inverts every line, flips the original POSTED → REVERSED,
 * and runs the resolved reversal date through the B2 period gate (issue #944).
 *
 * <p>
 * Enqueued in the same transaction as the {@code PaymentApplicationReversal}
 * row (transactional outbox) by
 * {@link com.positivity.accounting.internal.service.PaymentApplicationServiceImpl}
 * and consumed by
 * {@link com.positivity.accounting.internal.handler.PaymentApplicationReversalGLPostingEventHandler}.
 *
 * <p>
 * Idempotency: keyed on {@code applicationRequestId}, so reversing one
 * application or every application of a multi-invoice request both produce
 * exactly one reversing entry (no double reversal).
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/958">Issue
 *      #958 (story C2)</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentApplicationReversalGLPostingEvent {

    /** Outbox event UUID (delivery identity, not the posting idempotency key). */
    @NonNull
    @JsonProperty("eventId")
    private UUID eventId;

    /**
     * Application request id of the original cash-receipt posting (C1). Both the
     * reversing-JE idempotency key and the {@code sourceEventId} used to locate
     * the original entry derive from it.
     */
    @NonNull
    @JsonProperty("applicationRequestId")
    private String applicationRequestId;

    /** The {@code PaymentApplicationReversal} row that triggered this posting. */
    @NonNull
    @JsonProperty("reversalId")
    private UUID reversalId;

    /** Receivable payment UUID the reversed application drew from. */
    @NonNull
    @JsonProperty("paymentId")
    private UUID paymentId;

    /** Reversal reason, carried onto the reversing journal entry description. */
    @NonNull
    @JsonProperty("reason")
    private String reason;
}
