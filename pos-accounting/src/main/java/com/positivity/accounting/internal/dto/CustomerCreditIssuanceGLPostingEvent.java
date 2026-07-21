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
 * Outbox work item enqueued when an overpayment converts its excess into a
 * {@link com.positivity.accounting.internal.entity.CustomerCredit} (story
 * parity-C1, issue #975), triggering asynchronous GL posting of the credit
 * issuance:
 * <ul>
 * <li>Dr Undeposited Funds (the overpayment cash actually received)</li>
 * <li>Cr Customer Credit Liability (the obligation now owed to the customer)</li>
 * </ul>
 *
 * <p>
 * Without this leg the overpayment cash never reaches the ledger: only the
 * applied portion posts (Dr Undeposited Funds / Cr AR via
 * {@link PaymentApplicationGLPostingEvent}), leaving Undeposited Funds
 * understated and the customer-credit liability unrecognized. This entry
 * restores double-entry completeness for the excess.
 *
 * <p>
 * Enqueued in the same transaction as the {@code CustomerCredit} insert
 * (transactional outbox) by
 * {@link com.positivity.accounting.internal.service.PaymentApplicationServiceImpl}
 * and consumed by
 * {@link com.positivity.accounting.internal.handler.CustomerCreditIssuanceGLPostingEventHandler}.
 * Accounts resolve through the {@code CUSTOMER_CREDIT_ISSUANCE} posting
 * category and its {@code UNDEPOSITED_FUNDS} / {@code CUSTOMER_CREDIT_LIABILITY}
 * mapping keys — never hardcoded.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/975">Issue
 *      #975 (parity-C1)</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerCreditIssuanceGLPostingEvent {

    /** Outbox event UUID (delivery identity, not the posting idempotency key). */
    @NonNull
    @JsonProperty("eventId")
    private UUID eventId;

    /**
     * Caller-supplied application request id — the basis for the posting
     * idempotency key (namespaced for the credit leg so it never collides with
     * the AR cash-receipt leg) and the journal entry {@code sourceEventId}.
     */
    @NonNull
    @JsonProperty("applicationRequestId")
    private String applicationRequestId;

    /** Persisted {@code CustomerCredit} id this issuance entry backs. */
    @NonNull
    @JsonProperty("creditId")
    private UUID creditId;

    /** Receivable payment UUID the overpayment drew from. */
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

    /** Overpayment excess recorded as the customer credit (the entry amount). */
    @NonNull
    @JsonProperty("creditAmount")
    private BigDecimal creditAmount;

    /** Timestamp the payment application (and credit) was recorded. */
    @NonNull
    @JsonProperty("applicationTimestamp")
    private Instant applicationTimestamp;
}
