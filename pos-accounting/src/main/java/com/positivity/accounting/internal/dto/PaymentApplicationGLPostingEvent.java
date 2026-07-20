package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * Outbox work item enqueued when a payment application to invoices succeeds
 * (AR cash receipt), triggering asynchronous GL posting of:
 * <ul>
 * <li>Dr Undeposited Funds (cash receipt held until settlement deposit)</li>
 * <li>Cr Accounts Receivable (invoice balance reduction)</li>
 * </ul>
 *
 * <p>
 * Per parity decision D-3 the debit side is always Undeposited Funds — cash
 * receipts are never posted straight to Cash; settlement reconciliation
 * clears Undeposited Funds to Cash later.
 *
 * <p>
 * Enqueued in the same transaction as the payment application (transactional
 * outbox) by
 * {@link com.positivity.accounting.internal.service.PaymentApplicationServiceImpl}
 * and consumed by
 * {@link com.positivity.accounting.internal.handler.PaymentApplicationGLPostingEventHandler}.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/954">Issue
 *      #954 (story C1)</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentApplicationGLPostingEvent {

    /** Outbox event UUID (delivery identity, not the posting idempotency key). */
    @NonNull
    @JsonProperty("eventId")
    private UUID eventId;

    /**
     * Caller-supplied application request id — the posting idempotency key and
     * the journal entry {@code sourceEventId} basis.
     */
    @NonNull
    @JsonProperty("applicationRequestId")
    private String applicationRequestId;

    /** Receivable payment UUID the application drew from. */
    @NonNull
    @JsonProperty("paymentId")
    private UUID paymentId;

    /** Customer UUID. */
    @NonNull
    @JsonProperty("customerId")
    private UUID customerId;

    /** Payment currency (ISO 4217). */
    @NonNull
    @JsonProperty("currency")
    private String currency;

    /** Total amount actually applied across all invoices in the request. */
    @NonNull
    @JsonProperty("appliedAmount")
    private BigDecimal appliedAmount;

    /** Timestamp the application was recorded. */
    @NonNull
    @JsonProperty("applicationTimestamp")
    private Instant applicationTimestamp;
}
