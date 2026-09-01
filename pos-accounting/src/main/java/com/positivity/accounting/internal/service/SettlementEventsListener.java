package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtInvoiceDepositCreditApplication;
import com.positivity.accounting.internal.entity.ExtInvoicePaymentReversal;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ExtInvoiceDepositCreditApplicationRepository;
import com.positivity.accounting.internal.repository.ExtInvoicePaymentReversalRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.payment.DepositCreditAppliedV1;
import com.positivity.domainevents.payment.PaymentReversedV1;
import com.positivity.domainevents.payment.PaymentSettledV1;
import com.positivity.domainevents.payment.SettlementReportedV1;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code payment.events.v1} settlement facts into accounting's settlement reconciliation
 * (story F1c, issue #963, decisions D-9…D-14), and — since issue #1537 (D4) — the per-payment
 * {@code payment.payment.settled} fact that materializes the AR-available {@link
 * com.positivity.accounting.internal.entity.ReceivablePayment} that the now-removed {@code
 * payment.cleared.v1} listener used to create. {@code PaymentApplicationServiceImpl
 * #handlePaymentCleared} is reused unchanged — this class only translates the envelope into its
 * existing parameters and inherits its {@code existsBySourceEventId} idempotency.
 *
 * <p><strong>Why extended, not a sibling listener:</strong> both facts arrive on {@code
 * payment.events.v1}. A second {@code @KafkaListener} on the same topic runs its own consumer
 * group, so Kafka delivers every record to both independently — appropriate when two listeners
 * have genuinely separate concerns, but here the added fact shares this listener's transaction
 * boundary, {@code processed_events} idempotency, and the "malformed payload is skipped-and-marked,
 * a business/DB error propagates unmarked for retry/DLQ" contract already proven for {@link
 * SettlementReportedV1} below (PR #977 finding 13). Splitting it out would buy nothing but a second
 * partition assignment and a second full read of the topic.
 *
 * <p><strong>Known gap (issue #1537 D4 finding):</strong> {@link PaymentSettledV1#partyId()} is
 * {@code @Nullable} — {@code null} for anonymous counter sales (see {@code
 * OrderInvoiceServiceImpl#buildDraftInvoice}, {@code InvoiceServiceImpl#createNewInvoice}) — while
 * {@code ReceivablePayment.customerId} is a non-null column and {@code handlePaymentCleared}'s
 * {@code customerId} parameter is {@code @NonNull}. A settled payment with no party id cannot be
 * faithfully turned into a customer-keyed receivable, so such events are logged and skipped rather
 * than given an invented customer id; see {@link #resolveCustomerId(String)}. This is not a
 * regression: {@code payment.cleared.v1} never had a producer, so no path created a receivable for
 * these payments before this change either.
 *
 * <p><strong>Since issues #1620/#1621:</strong> this listener also replicates two more {@code
 * payment.events.v1} facts, each into its own read-only table (ADR-0044 R3/R6) rather than through
 * an existing service, since neither has a pre-existing write path to reuse the way {@code
 * payment.payment.settled} reuses {@code handlePaymentCleared}:
 *
 * <ul>
 *   <li>{@code payment.payment.reversed} ({@link PaymentReversedV1}) — only completed refunds
 *       ({@code reversalType == "REFUND"}) are stored, into {@link
 *       com.positivity.accounting.internal.entity.ExtInvoicePaymentReversal}; see {@link
 *       #onPaymentReversed}.
 *   <li>{@code payment.deposit-credit.applied} ({@link DepositCreditAppliedV1}) — every draw-down
 *       is stored into {@link
 *       com.positivity.accounting.internal.entity.ExtInvoiceDepositCreditApplication}; see {@link
 *       #onDepositCreditApplied}.
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class SettlementEventsListener {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final SettlementReconciliationService reconciliationService;
    private final PaymentApplicationService paymentApplicationService;
    private final ExtInvoicePaymentReversalRepository extInvoicePaymentReversalRepository;
    private final ExtInvoiceDepositCreditApplicationRepository extInvoiceDepositCreditApplicationRepository;
    private final Counter payloadRejectedCounter;
    private final Counter paymentSettledUnmappableCounter;

    public SettlementEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            SettlementReconciliationService reconciliationService,
            PaymentApplicationService paymentApplicationService,
            ExtInvoicePaymentReversalRepository extInvoicePaymentReversalRepository,
            ExtInvoiceDepositCreditApplicationRepository extInvoiceDepositCreditApplicationRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.reconciliationService = reconciliationService;
        this.paymentApplicationService = paymentApplicationService;
        this.extInvoicePaymentReversalRepository = extInvoicePaymentReversalRepository;
        this.extInvoiceDepositCreditApplicationRepository = extInvoiceDepositCreditApplicationRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", "payment")
                        .tag("entity", "settlement-events")
                        .register(registry);
        this.paymentSettledUnmappableCounter = registry == null
                ? null
                : Counter.builder("payment.settled.unmappable")
                        .description("payment.payment.settled events skipped because they carry no usable party id "
                                + "(e.g. an anonymous counter sale) and so cannot be turned into a "
                                + "customer-keyed ReceivablePayment (issue #1537 D4)")
                        .tag("owner", "payment")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.accounting.kafka.payment-events-topic:payment.events.v1}",
            groupId = "pos-accounting-settlement-events")
    @Transactional
    public void onPaymentEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable payment event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        boolean isSettlementReported = SettlementReportedV1.EVENT_TYPE.equals(eventType);
        boolean isPaymentSettled = PaymentSettledV1.EVENT_TYPE.equals(eventType);
        boolean isPaymentReversed = PaymentReversedV1.EVENT_TYPE.equals(eventType);
        boolean isDepositCreditApplied = DepositCreditAppliedV1.EVENT_TYPE.equals(eventType);
        if (!isSettlementReported && !isPaymentSettled && !isPaymentReversed && !isDepositCreditApplied) {
            log.debug("Ignoring payment event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping payment event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate payment event eventId={}", eventId);
            return;
        }

        if (isPaymentSettled) {
            onPaymentSettled(envelope, eventId);
            return;
        }
        if (isPaymentReversed) {
            onPaymentReversed(envelope, eventId);
            return;
        }
        if (isDepositCreditApplied) {
            onDepositCreditApplied(envelope, eventId);
            return;
        }

        // Only deserialization/validation failures are terminal for this record — skip and mark
        // processed so a malformed payload does not poison the partition. Anything thrown by
        // ingest (transient DB errors and unexpected failures alike) propagates unwrapped for
        // container retry / DLQ, rather than being silently swallowed and marked processed
        // (finding 13).
        SettlementReportedV1 payload;
        try {
            payload = objectMapper.treeToValue(envelope.path("payload"), SettlementReportedV1.class);
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed settlement event payload eventId={}: {}", eventId, e.getMessage(), e);
            markProcessed(eventId);
            return;
        } catch (Exception e) {
            log.warn("Skipping malformed settlement event eventId={}", eventId, e);
            markProcessed(eventId);
            return;
        }

        reconciliationService.ingestSettlement(payload);
        markProcessed(eventId);
    }

    /**
     * Handle {@code payment.payment.settled}: materialize the AR-available {@code
     * ReceivablePayment} via {@link PaymentApplicationService#handlePaymentCleared}. Same
     * "malformed payload skipped-and-marked, business/DB error propagates unmarked" contract as
     * {@link #onPaymentEvent} above — the {@code handlePaymentCleared} call below is deliberately
     * outside any catch block so a failure there reaches the container's retry/DLQ handling rather
     * than being swallowed.
     */
    private void onPaymentSettled(@NonNull JsonNode envelope, @NonNull String eventId) {
        PaymentSettledV1 payload;
        try {
            payload = objectMapper.treeToValue(envelope.path("payload"), PaymentSettledV1.class);
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed payment.payment.settled payload eventId={}: {}", eventId, e.getMessage(), e);
            markProcessed(eventId);
            return;
        } catch (Exception e) {
            log.warn("Skipping malformed payment.payment.settled event eventId={}", eventId, e);
            markProcessed(eventId);
            return;
        }

        if (!hasRequiredFieldsForReceivable(payload)) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error(
                    "Rejected payment.payment.settled payload missing required fields eventId={} paymentIntentId={}",
                    eventId,
                    payload.paymentIntentId());
            markProcessed(eventId);
            return;
        }

        UUID customerId = resolveCustomerId(payload.partyId());
        if (customerId == null) {
            if (paymentSettledUnmappableCounter != null) {
                paymentSettledUnmappableCounter.increment();
            }
            log.warn(
                    "Skipping payment.payment.settled eventId={} paymentIntentId={} invoiceId={}: no usable party"
                            + " id (e.g. an anonymous counter sale, issue #1537 D4 finding) — accounting cannot"
                            + " materialize a ReceivablePayment without a customer id",
                    eventId,
                    payload.paymentIntentId(),
                    payload.invoiceId());
            markProcessed(eventId);
            return;
        }

        // The sourceEventId parameter below requires a genuine UUID (it's a UUID column on
        // ReceivablePayment); a non-UUID eventId is a malformed-envelope condition, not a business
        // or DB error, so it follows the same "skip and mark processed" contract as the payload
        // parse failures above rather than propagating for retry/DLQ.
        UUID eventUuid;
        try {
            eventUuid = UUID.fromString(eventId);
        } catch (IllegalArgumentException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.warn("Skipping payment.payment.settled event with non-UUID eventId={}", eventId, e);
            markProcessed(eventId);
            return;
        }

        paymentApplicationService.handlePaymentCleared(
                payload.paymentIntentId(),
                customerId,
                payload.currencyCode(),
                payload.amount(),
                payload.settledAt(),
                eventUuid);
        markProcessed(eventId);
    }

    /**
     * Handle {@code payment.payment.reversed}: replicate completed refunds (issue #1620) into
     * {@link ExtInvoicePaymentReversal}. Same "malformed payload skipped-and-marked, business/DB
     * error propagates unmarked" contract as {@link #onPaymentEvent} above — the repository save
     * below is deliberately outside any catch block.
     *
     * <p>VOID reversals (and any future reversal type other than REFUND) are intentionally not
     * stored: a VOID releases an authorization that never captured funds, so it never produced a
     * {@code PaymentApplication} and removes no collected cash — recording it here would subtract
     * money that was never added.
     */
    private void onPaymentReversed(@NonNull JsonNode envelope, @NonNull String eventId) {
        PaymentReversedV1 payload;
        try {
            payload = objectMapper.treeToValue(envelope.path("payload"), PaymentReversedV1.class);
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed payment.payment.reversed payload eventId={}: {}", eventId, e.getMessage(), e);
            markProcessed(eventId);
            return;
        } catch (Exception e) {
            log.warn("Skipping malformed payment.payment.reversed event eventId={}", eventId, e);
            markProcessed(eventId);
            return;
        }

        if (payload == null) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected payment.payment.reversed event with missing payload eventId={}", eventId);
            markProcessed(eventId);
            return;
        }

        // A null reversalType is malformed, not a VOID: treating it as VOID would silently drop
        // a reversal fact instead of rejecting it — fail loud rather than falling into the
        // REFUND/VOID branch below.
        if (payload.reversalType() == null) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error(
                    "Rejected payment.payment.reversed payload with null reversalType eventId={} refundId={}",
                    eventId,
                    payload.refundId());
            markProcessed(eventId);
            return;
        }

        if (!"REFUND".equals(payload.reversalType())) {
            log.debug(
                    "Ignoring non-REFUND payment.payment.reversed eventId={} reversalType={} (issue #1620: a VOID"
                            + " releases an authorization that never captured funds)",
                    eventId,
                    payload.reversalType());
            markProcessed(eventId);
            return;
        }

        if (!hasRequiredFieldsForReversal(payload)) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error(
                    "Rejected payment.payment.reversed payload missing required fields eventId={} refundId={}",
                    eventId,
                    payload.refundId());
            markProcessed(eventId);
            return;
        }

        // refundId is the replica's primary key; a replay-repair event can carry a fresh eventId
        // for the same underlying refund fact, so the natural key is the authoritative guard here,
        // not just the outer processed_events check on eventId.
        if (extInvoicePaymentReversalRepository.existsById(payload.refundId())) {
            log.debug("Skipping already-replicated refund refundId={} eventId={}", payload.refundId(), eventId);
            markProcessed(eventId);
            return;
        }

        UUID eventUuid;
        try {
            eventUuid = UUID.fromString(eventId);
        } catch (IllegalArgumentException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.warn("Skipping payment.payment.reversed event with non-UUID eventId={}", eventId, e);
            markProcessed(eventId);
            return;
        }

        extInvoicePaymentReversalRepository.save(ExtInvoicePaymentReversal.builder()
                .refundId(payload.refundId())
                .paymentIntentId(payload.paymentIntentId())
                .invoiceId(payload.invoiceId())
                .partyId(payload.partyId())
                .amount(payload.amount())
                .currencyCode(payload.currencyCode())
                .reversalType(payload.reversalType())
                .reversedAt(payload.reversedAt())
                .sourceEventId(eventUuid)
                .build());
        markProcessed(eventId);
    }

    /**
     * Required fields for a REFUND {@link ExtInvoicePaymentReversal} row: {@code refundId} is the
     * replica's primary key, and {@code invoiceId}/{@code paymentIntentId}/{@code partyId} are
     * legitimately {@code null} for a standalone refund with no gateway or invoice leg (#1620).
     */
    private static boolean hasRequiredFieldsForReversal(@NonNull PaymentReversedV1 payload) {
        return payload.refundId() != null
                && payload.amount() != null
                && payload.amount().compareTo(BigDecimal.ZERO) > 0
                && payload.currencyCode() != null
                && !payload.currencyCode().isBlank()
                && payload.reversedAt() != null;
    }

    /**
     * Handle {@code payment.deposit-credit.applied}: replicate a deposit-credit draw-down (issue
     * #1621) into {@link ExtInvoiceDepositCreditApplication}. Same "malformed payload
     * skipped-and-marked, business/DB error propagates unmarked" contract as {@link
     * #onPaymentEvent} above.
     */
    private void onDepositCreditApplied(@NonNull JsonNode envelope, @NonNull String eventId) {
        DepositCreditAppliedV1 payload;
        try {
            payload = objectMapper.treeToValue(envelope.path("payload"), DepositCreditAppliedV1.class);
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error(
                    "Rejected malformed payment.deposit-credit.applied payload eventId={}: {}",
                    eventId,
                    e.getMessage(),
                    e);
            markProcessed(eventId);
            return;
        } catch (Exception e) {
            log.warn("Skipping malformed payment.deposit-credit.applied event eventId={}", eventId, e);
            markProcessed(eventId);
            return;
        }

        if (payload == null) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected payment.deposit-credit.applied event with missing payload eventId={}", eventId);
            markProcessed(eventId);
            return;
        }

        if (!hasRequiredFieldsForDepositCreditApplication(payload)) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error(
                    "Rejected payment.deposit-credit.applied payload missing required fields eventId={}"
                            + " depositCreditId={} invoiceId={}",
                    eventId,
                    payload.depositCreditId(),
                    payload.invoiceId());
            markProcessed(eventId);
            return;
        }

        // pos-invoice's applyAvailableCredits() applies a given credit to a given invoice at most
        // once, so the (depositCreditId, invoiceId) pair is the authoritative duplicate guard — a
        // replay-repair event can carry a fresh eventId for the same underlying application fact.
        if (extInvoiceDepositCreditApplicationRepository.existsByDepositCreditIdAndInvoiceId(
                payload.depositCreditId(), payload.invoiceId())) {
            log.debug(
                    "Skipping already-replicated deposit-credit application depositCreditId={} invoiceId={}"
                            + " eventId={}",
                    payload.depositCreditId(),
                    payload.invoiceId(),
                    eventId);
            markProcessed(eventId);
            return;
        }

        UUID eventUuid;
        try {
            eventUuid = UUID.fromString(eventId);
        } catch (IllegalArgumentException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.warn("Skipping payment.deposit-credit.applied event with non-UUID eventId={}", eventId, e);
            markProcessed(eventId);
            return;
        }

        extInvoiceDepositCreditApplicationRepository.save(ExtInvoiceDepositCreditApplication.builder()
                .depositCreditId(payload.depositCreditId())
                .invoiceId(payload.invoiceId())
                .amountApplied(payload.amountApplied())
                .appliedAt(payload.appliedAt())
                .sourceEventId(eventUuid)
                .build());
        markProcessed(eventId);
    }

    /** Required fields for an {@link ExtInvoiceDepositCreditApplication} row (issue #1621). */
    private static boolean hasRequiredFieldsForDepositCreditApplication(@NonNull DepositCreditAppliedV1 payload) {
        return payload.depositCreditId() != null
                && payload.invoiceId() != null
                && payload.amountApplied() != null
                && payload.amountApplied().compareTo(BigDecimal.ZERO) > 0
                && payload.appliedAt() != null;
    }

    /**
     * Defensive re-check of the fields {@code handlePaymentCleared} needs: {@link
     * PaymentSettledV1}'s canonical constructor marks these {@code @NonNull}, but Jackson does not
     * enforce that at deserialization time, so an omitted field would otherwise reach the service
     * as a silent {@code null} instead of being rejected like {@code PaymentClearedEvent#validate()}
     * used to reject it.
     */
    private static boolean hasRequiredFieldsForReceivable(@NonNull PaymentSettledV1 payload) {
        return payload.paymentIntentId() != null
                && payload.amount() != null
                && payload.amount().compareTo(BigDecimal.ZERO) > 0
                && payload.currencyCode() != null
                && !payload.currencyCode().isBlank()
                && payload.settledAt() != null;
    }

    /**
     * {@link PaymentSettledV1#partyId()} is a nullable free-form string; {@code
     * ReceivablePayment.customerId} is a non-null UUID column. Returns {@code null} — never an
     * invented id — when there is no usable party id, so the caller can skip the event instead of
     * fabricating a customer (see the class javadoc "Known gap").
     */
    @Nullable
    private static UUID resolveCustomerId(@Nullable String partyId) {
        if (partyId == null || partyId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(partyId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void markProcessed(@NonNull String eventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now(clock))
                .build());
    }
}
