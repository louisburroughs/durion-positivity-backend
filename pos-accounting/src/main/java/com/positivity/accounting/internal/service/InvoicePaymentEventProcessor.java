package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.AccountingEventTypeRegistry;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.exception.AccountingEventRejectedException;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes a received {@code INVOICE_PAYMENT} accounting event (#2435) by recording it in the AR
 * subledger, never by posting a journal entry of its own.
 *
 * <p>The payment becomes a {@link ReceivablePayment} keyed on the Payment-domain {@code paymentId},
 * then is applied to its invoice through {@link PaymentApplicationService}. The application's own
 * GL work item posts Dr Undeposited Funds / Cr Accounts Receivable, so AR is reduced only by the
 * application record (AD-002), the Undeposited Funds debit stays matchable by settlement
 * reconciliation, and a later {@code payment.events.v1} fact for the same {@code paymentId} finds
 * the payment already recorded instead of booking it twice. An amount beyond the invoice's balance,
 * or a payment for an invoice already paid in full, becomes a {@code CustomerCredit} (AD-003).
 *
 * <p>Payload (the submitted event's {@code payload} object): {@code paymentId}, {@code invoiceId},
 * {@code amountPaid} (&gt; 0), {@code currency} (the ledger's) and {@code paidAt} (ISO-8601 instant)
 * are required; {@code customerId} is optional and, when present, must match the invoice's customer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoicePaymentEventProcessor {

    public static final String EVENT_TYPE = AccountingEventTypeRegistry.INVOICE_PAYMENT;

    /** The invoice is not in the replica yet; replica lag makes this retryable. */
    static final String INVOICE_NOT_FOUND = "INVOICE_NOT_FOUND";

    /** The invoice is voided, cancelled or not finalized: no AR to apply against. */
    static final String INVOICE_NOT_ELIGIBLE = "INVOICE_NOT_ELIGIBLE";

    /** The same {@code paymentId} was already recorded with different details. */
    static final String DUPLICATE_CONFLICT = PostingFailureReason.INVOICE_PAYMENT_DUPLICATE_CONFLICT;

    /** A required payload field is missing or malformed. */
    static final String INVALID_PAYLOAD = PostingFailureReason.INVOICE_PAYMENT_INVALID_PAYLOAD;

    static final String REQUEST_ID_PREFIX = EVENT_TYPE + ":";

    private final PaymentApplicationService paymentApplicationService;
    private final ReceivablePaymentRepository receivablePaymentRepository;
    private final InvoiceBalanceCalculator invoiceBalanceCalculator;
    private final LedgerCurrency ledgerCurrency;
    private final Clock clock;

    /**
     * Record one received event in the subledger and mark it {@code PROCESSED}, inside the caller's
     * transaction so the payment, its application and the event's status commit together.
     *
     * @throws AccountingEventRejectedException when the event cannot be processed as received
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void process(@NonNull AccountingEvent event) {
        InvoicePayment payment = parse(event);

        if (ledgerCurrency.isForeign(payment.currency())) {
            throw new AccountingEventRejectedException(
                    AccountingEventStatus.SUSPENDED,
                    PostingFailureReason.CURRENCY_NOT_SUPPORTED.name(),
                    "Payment is in " + payment.currency() + " but the ledger books " + ledgerCurrency.code()
                            + " only (ADR-0067 PC-9)");
        }

        ExtInvoice invoice = invoiceBalanceCalculator
                .findInvoice(payment.invoiceId())
                .orElseThrow(() -> new AccountingEventRejectedException(
                        AccountingEventStatus.SUSPENDED,
                        INVOICE_NOT_FOUND,
                        "Invoice " + payment.invoiceId() + " is not in the invoice replica yet; reprocess once"
                                + " it arrives"));
        if (!invoiceBalanceCalculator.isArEligible(invoice)) {
            throw new AccountingEventRejectedException(
                    AccountingEventStatus.FAILED,
                    INVOICE_NOT_ELIGIBLE,
                    "Invoice " + payment.invoiceId() + " cannot take a payment (status: " + invoice.getStatus() + ")");
        }
        UUID customerId = invoiceCustomer(invoice, payment);

        // What this event asks to apply: the whole payment when this event records it, or what is
        // still unapplied when another path recorded it first.
        BigDecimal toApply = payment.amountPaid();
        Optional<ReceivablePayment> existing = receivablePaymentRepository.findById(payment.paymentId());
        if (existing.isPresent()) {
            ReceivablePayment recorded = existing.get();
            if (!sameDetails(recorded, payment, customerId)) {
                throw new AccountingEventRejectedException(
                        AccountingEventStatus.FAILED,
                        DUPLICATE_CONFLICT,
                        "Payment " + payment.paymentId() + " is already recorded with a different amount,"
                                + " currency or customer");
            }
            if (!event.getEventId().equals(recorded.getSourceEventId())) {
                // Recorded by another path first (a payment.events.v1 fact only records the payment;
                // it applies nothing). Apply what is still unapplied; when nothing is, the payment is
                // already fully in the subledger and this event adds nothing.
                BigDecimal unapplied = recorded.getUnappliedAmount();
                if (recorded.getStatus() != ReceivablePayment.ReceivablePaymentStatus.AVAILABLE
                        || unapplied == null
                        || unapplied.signum() <= 0) {
                    markProcessed(event, payment, IdempotencyOutcome.DUPLICATE_IGNORED);
                    return;
                }
                toApply = unapplied;
            }
        } else {
            paymentApplicationService.handlePaymentCleared(
                    payment.paymentId(),
                    customerId,
                    payment.currency(),
                    payment.amountPaid(),
                    payment.paidAt(),
                    event.getEventId());
        }

        // Deterministic per event, so a replay never applies or credits twice (AD-010).
        String requestId = REQUEST_ID_PREFIX + event.getEventId();
        if (invoiceBalanceCalculator.balanceDue(invoice).compareTo(BigDecimal.ZERO) > 0) {
            // Capped at the balance due; any excess becomes a CustomerCredit inside the application.
            paymentApplicationService.applyPaymentToInvoices(
                    payment.paymentId(),
                    new PaymentApplicationRequest(
                            requestId,
                            List.of(new PaymentApplicationRequest.InvoiceApplication(payment.invoiceId(), toApply)),
                            null));
        } else {
            paymentApplicationService.creditUnappliedPayment(payment.paymentId(), requestId);
        }
        markProcessed(event, payment, IdempotencyOutcome.NEW);
    }

    private void markProcessed(
            @NonNull AccountingEvent event, @NonNull InvoicePayment payment, @NonNull IdempotencyOutcome outcome) {
        event.setStatus(AccountingEventStatus.PROCESSED);
        event.setIdempotencyOutcome(outcome.name());
        event.setInvoiceId(payment.invoiceId());
        event.setDomainKeyId(payment.paymentId().toString());
        event.setProcessedAt(Instant.now(clock));
        event.setFailureReasonCode(null);
        event.setFailureDetails(null);
        event.setErrorMessage(null);
        log.info(
                "Processed {} event {}: payment {} on invoice {} ({})",
                EVENT_TYPE,
                event.getEventId(),
                payment.paymentId(),
                payment.invoiceId(),
                outcome);
    }

    private @NonNull UUID invoiceCustomer(@NonNull ExtInvoice invoice, @NonNull InvoicePayment payment) {
        UUID customerId;
        try {
            customerId = invoice.getPartyId() == null ? null : UUID.fromString(invoice.getPartyId());
        } catch (IllegalArgumentException e) {
            customerId = null;
        }
        if (customerId == null) {
            throw new AccountingEventRejectedException(
                    AccountingEventStatus.FAILED,
                    INVOICE_NOT_ELIGIBLE,
                    "Invoice " + payment.invoiceId() + " carries no customer to record the payment against");
        }
        if (payment.customerId() != null && !payment.customerId().equals(customerId)) {
            // A customer is assigned once and never changed (AD-004): trust the invoice, refuse the event.
            throw new AccountingEventRejectedException(
                    AccountingEventStatus.FAILED,
                    INVALID_PAYLOAD,
                    "customerId " + payment.customerId() + " does not match invoice " + payment.invoiceId()
                            + "'s customer");
        }
        return customerId;
    }

    private static boolean sameDetails(
            @NonNull ReceivablePayment recorded, @NonNull InvoicePayment payment, @NonNull UUID customerId) {
        return recorded.getTotalAmount() != null
                && recorded.getTotalAmount().compareTo(payment.amountPaid()) == 0
                && Objects.equals(recorded.getCustomerId(), customerId)
                && payment.currency().equalsIgnoreCase(String.valueOf(recorded.getCurrency()));
    }

    private static @NonNull InvoicePayment parse(@NonNull AccountingEvent event) {
        Map<String, Object> envelope = event.getPayload();
        Object body = envelope == null ? null : envelope.get("payload");
        if (!(body instanceof Map<?, ?> fields)) {
            throw invalid("event carries no payload object");
        }
        String currency = text(fields.get("currency"), "currency").trim().toUpperCase(Locale.ROOT);
        BigDecimal amountPaid = atCurrencyScale(decimal(fields.get("amountPaid"), "amountPaid"), currency);
        if (amountPaid.signum() <= 0) {
            throw invalid("amountPaid must be greater than 0");
        }
        return new InvoicePayment(
                uuid(fields.get("paymentId"), "paymentId"),
                uuid(fields.get("invoiceId"), "invoiceId"),
                amountPaid,
                currency,
                instant(fields.get("paidAt")),
                fields.get("customerId") == null ? null : uuid(fields.get("customerId"), "customerId"));
    }

    /** The amount at the currency's minor-unit scale, HALF_UP, as every subledger amount is kept. */
    private static @NonNull BigDecimal atCurrencyScale(@NonNull BigDecimal amount, @NonNull String currency) {
        int scale;
        try {
            scale = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        } catch (IllegalArgumentException e) {
            throw invalid("currency " + currency + " is not an ISO-4217 code");
        }
        return amount.setScale(scale, RoundingMode.HALF_UP);
    }

    private static @NonNull UUID uuid(@Nullable Object value, @NonNull String field) {
        try {
            return UUID.fromString(text(value, field));
        } catch (IllegalArgumentException e) {
            throw invalid(field + " is not a UUID");
        }
    }

    private static @NonNull BigDecimal decimal(@Nullable Object value, @NonNull String field) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(text(value, field));
        } catch (NumberFormatException e) {
            throw invalid(field + " is not a number");
        }
    }

    private static @NonNull Instant instant(@Nullable Object value) {
        String text = text(value, "paidAt");
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            throw invalid("paidAt is not an ISO-8601 instant");
        }
    }

    private static @NonNull String text(@Nullable Object value, @NonNull String field) {
        if (value == null || value.toString().isBlank()) {
            throw invalid(field + " is required");
        }
        return value.toString();
    }

    private static AccountingEventRejectedException invalid(@NonNull String detail) {
        return new AccountingEventRejectedException(
                AccountingEventStatus.FAILED, INVALID_PAYLOAD, EVENT_TYPE + " payload invalid: " + detail);
    }

    /** The fields of an {@code INVOICE_PAYMENT} payload this processor reads. */
    record InvoicePayment(
            @NonNull UUID paymentId,
            @NonNull UUID invoiceId,
            @NonNull BigDecimal amountPaid,
            @NonNull String currency,
            @NonNull Instant paidAt,
            @Nullable UUID customerId) {}
}
