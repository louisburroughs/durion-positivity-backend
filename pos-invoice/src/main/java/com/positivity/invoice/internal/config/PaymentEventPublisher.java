package com.positivity.invoice.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.payment.PaymentReversedV1;
import com.positivity.domainevents.payment.PaymentSettledV1;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.PaymentIntent;
import com.positivity.invoice.internal.entity.RefundRecord;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
 * <p>No-op when the Kafka feature flag ({@code pos.invoice.kafka.enabled}) is off — the
 * {@link OutboxEventWriter} bean is conditional, so this publisher degrades gracefully. Must be
 * called inside the mutating transaction (the writer requires {@code MANDATORY} propagation).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private static final String SOURCE_SERVICE = "pos-invoice";
    private static final String CURRENCY = "USD";
    /** All pos-invoice payment intents settle through the card gateway today. */
    private static final String GATEWAY_METHOD_TYPE = "CARD";

    private final Clock clock;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    /** Emits {@code payment.payment.settled} for a just-captured intent. */
    public void publishPaymentSettled(@NonNull PaymentIntent paymentIntent) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        Invoice invoice = paymentIntent.getInvoice();
        PaymentSettledV1 payload = new PaymentSettledV1(
                paymentIntent.getId(),
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getOrderId(),
                invoice.getWorkorderId(),
                invoice.getPartyId(),
                GATEWAY_METHOD_TYPE,
                paymentIntent.getCapturedAmount(),
                CURRENCY,
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
                CURRENCY,
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
