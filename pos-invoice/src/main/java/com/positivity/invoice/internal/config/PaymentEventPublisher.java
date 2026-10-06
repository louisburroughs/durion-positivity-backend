package com.positivity.invoice.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.payment.DepositCreditAppliedV1;
import com.positivity.domainevents.payment.PaymentReversedV1;
import com.positivity.domainevents.payment.PaymentSettledV1;
import com.positivity.invoice.internal.entity.DepositCredit;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.PaymentIntent;
import com.positivity.invoice.internal.entity.RefundRecord;
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
import org.springframework.stereotype.Component;

/**
 * Emits per-payment settlement facts to the payment outbox topic (order parity plan gate V1,
 * story C3): {@code payment.payment.settled} when a PaymentIntent captures and
 * {@code payment.payment.reversed} on voids and refunds. pos-order's completion handshake is the
 * first consumer.
 *
 * <p>No-op when the Kafka rails are off (dev/test profiles) — the
 * {@link OutboxEventWriter} bean is a {@code @KafkaRails} bean, so this publisher degrades gracefully. Must be
 * called inside the mutating transaction (the writer requires {@code MANDATORY} propagation).
 */
@Slf4j
@Component
public class PaymentEventPublisher {

    private static final String SOURCE_SERVICE = "pos-invoice";
    /** All pos-invoice payment intents settle through the card gateway today. */
    private static final String GATEWAY_METHOD_TYPE = "CARD";

    /**
     * CAP:550 S9: a {@code payment.payment.settled} fact queued without a party. Fires only on a
     * defect — the finalization and payment backstops make a missing party unreachable — so a
     * non-zero rate is an alert (README "Payment settlement events").
     */
    static final String PARTY_MISSING_COUNTER = "payment.settled.party_missing";

    private final Clock clock;
    private final InvoiceCurrencySource currencySource;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final @Nullable Counter partyMissingCounter;

    public PaymentEventPublisher(
            @NonNull Clock clock,
            @NonNull InvoiceCurrencySource currencySource,
            @NonNull ObjectProvider<OutboxEventWriter> outboxEventWriter,
            @NonNull ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.currencySource = currencySource;
        this.outboxEventWriter = outboxEventWriter;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.partyMissingCounter = registry == null
                ? null
                : Counter.builder(PARTY_MISSING_COUNTER)
                        .description("payment.payment.settled facts queued without a partyId (defect: the"
                                + " finalization and payment backstops should make this unreachable)")
                        .register(registry);
    }

    /**
     * Emits {@code payment.payment.settled} for a just-captured intent.
     *
     * <p>CAP:550 S9 (spec §4.4 item 1): from schema version 2 the fact always names the party.
     * {@code PaymentServiceImpl} refuses to create or capture an intent on a party-less invoice,
     * so the party is present here. Should it ever be missing, the capture has already happened:
     * the fact is still queued (dropping it would leave pos-order unable to complete the order),
     * logged at ERROR and counted on {@value #PARTY_MISSING_COUNTER}; S11's accounting alert then
     * treats the event as a defect.
     */
    public void publishPaymentSettled(@NonNull PaymentIntent paymentIntent) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        Invoice invoice = paymentIntent.getInvoice();
        if (invoice.getPartyId() == null || invoice.getPartyId().isBlank()) {
            log.error(
                    "payment.payment.settled queued without a partyId: paymentIntentId={} invoiceId={} invoiceNumber={}"
                            + " — the party backstops should make this unreachable; investigate as a defect",
                    paymentIntent.getId(),
                    invoice.getId(),
                    invoice.getInvoiceNumber());
            if (partyMissingCounter != null) {
                partyMissingCounter.increment();
            }
        }
        PaymentSettledV1 payload = new PaymentSettledV1(
                paymentIntent.getId(),
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getOrderId(),
                invoice.getWorkorderId(),
                invoice.getPartyId(),
                GATEWAY_METHOD_TYPE,
                paymentIntent.getCapturedAmount(),
                currencySource.currencyCode(),
                paymentIntent.getGatewayProvider(),
                paymentIntent.getGatewayReference(),
                Instant.now(clock));
        writer.publish(
                DomainTopics.events("payment"),
                DomainEventEnvelope.of(
                        PaymentSettledV1.EVENT_TYPE,
                        PaymentSettledV1.SCHEMA_VERSION,
                        paymentIntent.getId(),
                        0L,
                        SOURCE_SERVICE,
                        null,
                        null,
                        payload,
                        clock));
        log.debug(
                "Queued payment.payment.settled paymentIntentId={} invoiceId={}",
                paymentIntent.getId(),
                invoice.getId());
    }

    /** Emits {@code payment.deposit-credit.applied} for a deposit-credit draw-down against an invoice. */
    public void publishDepositCreditApplied(
            @NonNull DepositCredit credit, @NonNull UUID invoiceId, @NonNull BigDecimal amountApplied) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        DepositCreditAppliedV1 payload =
                new DepositCreditAppliedV1(credit.getDepositCreditId(), invoiceId, amountApplied, Instant.now(clock));
        writer.publish(
                DomainTopics.events("payment"),
                DomainEventEnvelope.of(
                        DepositCreditAppliedV1.EVENT_TYPE,
                        DepositCreditAppliedV1.SCHEMA_VERSION,
                        credit.getDepositCreditId(),
                        0L,
                        SOURCE_SERVICE,
                        null,
                        null,
                        payload,
                        clock));
        log.debug(
                "Queued payment.deposit-credit.applied depositCreditId={} invoiceId={}",
                credit.getDepositCreditId(),
                invoiceId);
    }

    /** Emits {@code payment.payment.reversed} for a void of an authorized intent. */
    public void publishPaymentVoided(@NonNull PaymentIntent paymentIntent, @Nullable String reasonCode) {
        Invoice invoice = paymentIntent.getInvoice();
        publishReversal(
                paymentIntent.getId(),
                null,
                invoice == null ? null : invoice.getId(),
                invoice == null ? null : invoice.getOrderId(),
                invoice == null ? null : invoice.getPartyId(),
                "VOID",
                paymentIntent.getAuthorizedAmount(),
                reasonCode);
    }

    /** Emits {@code payment.payment.reversed} for a completed refund (gateway or standalone). */
    public void publishPaymentRefunded(@NonNull RefundRecord refund) {
        Invoice invoice = refund.getInvoice();
        publishReversal(
                refund.getPaymentIntent() == null
                        ? null
                        : refund.getPaymentIntent().getId(),
                refund.getId(),
                invoice == null ? null : invoice.getId(),
                invoice == null ? null : invoice.getOrderId(),
                refund.getPartyId(),
                "REFUND",
                refund.getAmount(),
                refund.getReason() == null ? null : refund.getReason().name());
    }

    private void publishReversal(
            @Nullable UUID paymentIntentId,
            @Nullable UUID refundId,
            @Nullable UUID invoiceId,
            @Nullable UUID orderId,
            @Nullable String partyId,
            @NonNull String reversalType,
            @NonNull BigDecimal amount,
            @Nullable String reasonCode) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        PaymentReversedV1 payload = new PaymentReversedV1(
                paymentIntentId,
                refundId,
                invoiceId,
                orderId,
                partyId,
                reversalType,
                amount,
                currencySource.currencyCode(),
                reasonCode,
                Instant.now(clock));
        UUID aggregateId = paymentIntentId != null ? paymentIntentId : refundId;
        writer.publish(
                DomainTopics.events("payment"),
                DomainEventEnvelope.of(
                        PaymentReversedV1.EVENT_TYPE,
                        PaymentReversedV1.SCHEMA_VERSION,
                        aggregateId,
                        0L,
                        SOURCE_SERVICE,
                        null,
                        null,
                        payload,
                        clock));
        log.debug("Queued payment.payment.reversed type={} paymentIntentId={}", reversalType, paymentIntentId);
    }
}
