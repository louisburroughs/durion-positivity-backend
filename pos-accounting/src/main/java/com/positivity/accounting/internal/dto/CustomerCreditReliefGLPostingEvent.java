package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.accounting.internal.enums.CustomerCreditTransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Outbox work item enqueued when a {@link com.positivity.accounting.internal.entity.CustomerCredit}
 * is drawn down (story parity-C1 follow-on, issue #992), triggering asynchronous GL posting of
 * the liability <em>relief</em>:
 *
 * <ul>
 *   <li>{@code APPLICATION} → Dr Customer Credit Liability / Cr Accounts Receivable — the credit
 *       settles a receivable, so both the liability and AR go down;</li>
 *   <li>{@code REFUND} → Dr Customer Credit Liability / Cr Undeposited Funds — the liability goes
 *       down and cash goes out.</li>
 * </ul>
 *
 * <p>This is the mirror of {@link CustomerCreditIssuanceGLPostingEvent}, which only ever
 * <em>recognizes</em> the liability. Across issuance → application/refund, a fully-consumed
 * credit nets the Customer Credit Liability (2300) control account back to zero, which is what
 * closes the #975 AC-7 subledger↔GL invariant.
 *
 * <p>Enqueued in the same transaction as the {@code CustomerCreditTransaction} insert
 * (transactional outbox, MANDATORY propagation) and consumed by
 * {@link com.positivity.accounting.internal.handler.CustomerCreditReliefGLPostingEventHandler}.
 * Accounts resolve through the {@code CUSTOMER_CREDIT_APPLICATION} /
 * {@code CUSTOMER_CREDIT_REFUND} posting categories and their mapping keys — never hardcoded.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/992">Issue
 *      #992 (parity-C1)</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerCreditReliefGLPostingEvent {

    /** Outbox event UUID (delivery identity, not the posting idempotency key). */
    @NonNull
    @JsonProperty("eventId")
    private UUID eventId;

    /**
     * Caller-supplied request id — the basis for the posting idempotency key (namespaced per
     * relief type) and the journal entry {@code sourceEventId}.
     */
    @NonNull
    @JsonProperty("requestId")
    private String requestId;

    /** The {@code CustomerCreditTransaction} row this entry backs. */
    @NonNull
    @JsonProperty("creditTransactionId")
    private UUID creditTransactionId;

    /** The {@code CustomerCredit} being relieved. */
    @NonNull
    @JsonProperty("creditId")
    private UUID creditId;

    /** Which relief this is; selects the posting category and therefore the contra account. */
    @NonNull
    @JsonProperty("transactionType")
    private CustomerCreditTransactionType transactionType;

    /** Invoice settled by an {@code APPLICATION}; null for a {@code REFUND}. */
    @Nullable
    @JsonProperty("invoiceId")
    private UUID invoiceId;

    /** Customer UUID. */
    @NonNull
    @JsonProperty("customerId")
    private UUID customerId;

    /** Credit currency (ISO 4217). */
    @NonNull
    @JsonProperty("currency")
    private String currency;

    /** Amount drawn down (the entry amount). */
    @NonNull
    @JsonProperty("amount")
    private BigDecimal amount;

    /**
     * Business timestamp of the draw-down. Drives the transaction date, so the entry lands in
     * the period the relief actually happened in and resolves the same effective-dated GL
     * mapping across replays.
     */
    @NonNull
    @JsonProperty("reliefTimestamp")
    private Instant reliefTimestamp;
}
