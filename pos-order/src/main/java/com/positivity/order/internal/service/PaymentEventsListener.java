package com.positivity.order.internal.service;

import com.positivity.domainevents.order.OrderCompletedV1;
import com.positivity.domainevents.payment.PaymentReversedV1;
import com.positivity.domainevents.payment.PaymentSettledV1;
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.entity.OrderPaymentRecord;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Settlement handshake consumer (parity story C3, spec R4.2–R4.4, R2.2): applies pos-invoice
 * {@code payment.payment.settled} / {@code payment.payment.reversed} facts to the order's payment
 * ledger, maintains {@code amountPaid}/{@code balanceDue}, and completes the order —
 * {@code PENDING_PAYMENT → COMPLETED} with {@code order.order.completed} — exactly when the
 * balance settles to zero (cent-exact, no rounding tolerance). Over-settlement raises
 * {@code order.payment.integrity-alert}, never silence.
 *
 * <p>Same consumer contract as the replica listeners: idempotent via {@code processed_events} in
 * the applying transaction, transient DB errors rethrown for container retry, malformed payloads
 * logged and skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.order.kafka", name = "enabled", havingValue = "true")
public class PaymentEventsListener {
    private static final String OTHER = "OTHER";

    static final String OWNER = "payment";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderPaymentRecordRepository paymentRecordRepository;
    private final OrderStateMachine orderStateMachine;
    private final OrderDomainEventPublisher domainEventPublisher;

    @KafkaListener(
            topics = "${pos.order.kafka.payment-events-topic:payment.events.v1}",
            groupId = "${pos.order.kafka.payment-events-consumer-group:pos-order-payment-events}")
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
        boolean settled = PaymentSettledV1.EVENT_TYPE.equals(eventType);
        boolean reversed = PaymentReversedV1.EVENT_TYPE.equals(eventType);
        if (!settled && !reversed) {
            log.debug("Ignoring payment event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping payment event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate payment event eventId={}", eventId);
            return;
        }

        try {
            JsonNode payload = envelope.path("payload");
            SalesOrder order = resolveOrder(payload);
            if (order == null) {
                // Not an order-fronted payment (e.g. a plain workorder invoice) — nothing to do,
                // but still record the event as processed so redelivery stays quiet.
                log.debug("Payment event eventId={} has no matching order; ignoring", eventId);
            } else if (settled) {
                applySettled(order, payload);
            } else {
                applyReversed(order, payload);
            }
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(eventId)
                    .owner(OWNER)
                    .processedAt(Instant.now(clock))
                    .build());
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed payment event eventId={}", eventId, e);
        }
    }

    @Nullable
    private SalesOrder resolveOrder(JsonNode payload) {
        UUID orderId = uuid(payload, "orderId");
        if (orderId != null) {
            SalesOrder order = salesOrderRepository.findById(orderId).orElse(null);
            if (order != null) {
                return order;
            }
        }
        UUID invoiceId = uuid(payload, "invoiceId");
        return invoiceId == null
                ? null
                : salesOrderRepository.findByInvoiceId(invoiceId).orElse(null);
    }

    private void applySettled(SalesOrder order, JsonNode payload) {
        paymentRecordRepository.save(OrderPaymentRecord.builder()
                .orderId(order.getOrderId())
                .recordType(OrderPaymentRecord.RecordType.SETTLED)
                .paymentIntentId(uuid(payload, "paymentIntentId"))
                .methodType(payload.path("methodType").stringValue(OTHER))
                .amount(money(payload, "amount"))
                .reference(payload.path("gatewayReference").stringValue(null))
                .occurredAt(instant(payload, "settledAt"))
                .build());
        recomputeSettlement(order);
    }

    private void applyReversed(SalesOrder order, JsonNode payload) {
        // A VOID releases a never-captured authorization: no settled money moved, so the ledger
        // (which only tracks settled funds) stays untouched.
        if (!"REFUND".equals(payload.path("reversalType").stringValue(null))) {
            log.debug("Ignoring VOID reversal for order {} (no settled funds)", order.getOrderId());
            return;
        }
        paymentRecordRepository.save(OrderPaymentRecord.builder()
                .orderId(order.getOrderId())
                .recordType(OrderPaymentRecord.RecordType.REVERSED)
                .paymentIntentId(uuid(payload, "paymentIntentId"))
                .refundId(uuid(payload, "refundId"))
                .methodType(OTHER)
                .amount(money(payload, "amount"))
                .occurredAt(instant(payload, "reversedAt"))
                .build());
        recomputeSettlement(order);
    }

    private void recomputeSettlement(SalesOrder order) {
        List<OrderPaymentRecord> records = paymentRecordRepository.findByOrderId(order.getOrderId());
        BigDecimal amountPaid = records.stream()
                .map(r -> r.getRecordType() == OrderPaymentRecord.RecordType.SETTLED
                        ? r.getAmount()
                        : r.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setAmountPaid(amountPaid);
        BigDecimal balance = order.getGrandTotal().subtract(amountPaid);
        order.setBalanceDue(balance.signum() < 0 ? BigDecimal.ZERO : balance);

        if (amountPaid.compareTo(order.getGrandTotal()) > 0) {
            domainEventPublisher.publishPaymentIntegrityAlert(order);
        }
        // Spec R2.2: complete exactly when the balance settles to zero, cent-exact.
        if (order.getStatus() == SalesOrderStatus.PENDING_PAYMENT && amountPaid.compareTo(order.getGrandTotal()) >= 0) {
            orderStateMachine.transition(order, SalesOrderStatus.COMPLETED, "settled in full");
            order.setUpdatedBy("system");
            domainEventPublisher.publishOrderCompleted(order, toTenders(records));
        }
        salesOrderRepository.save(order);
    }

    private static List<OrderCompletedV1.Tender> toTenders(List<OrderPaymentRecord> records) {
        Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
        for (OrderPaymentRecord record : records) {
            BigDecimal signed = record.getRecordType() == OrderPaymentRecord.RecordType.SETTLED
                    ? record.getAmount()
                    : record.getAmount().negate();
            String method = record.getRecordType() == OrderPaymentRecord.RecordType.SETTLED
                    ? record.getMethodType()
                    : resolveReversalMethod(records, record);
            byMethod.merge(method, signed, BigDecimal::add);
        }
        return byMethod.entrySet().stream()
                .filter(e -> e.getValue().signum() != 0)
                .map(e -> new OrderCompletedV1.Tender(e.getKey(), e.getValue()))
                .toList();
    }

    /** A reversal inherits its settlement's method when the intent matches; OTHER otherwise. */
    private static String resolveReversalMethod(List<OrderPaymentRecord> records, OrderPaymentRecord reversal) {
        if (reversal.getPaymentIntentId() == null) {
            return OTHER;
        }
        return records.stream()
                .filter(r -> r.getRecordType() == OrderPaymentRecord.RecordType.SETTLED)
                .filter(r -> reversal.getPaymentIntentId().equals(r.getPaymentIntentId()))
                .map(OrderPaymentRecord::getMethodType)
                .findFirst()
                .orElse(OTHER);
    }

    @Nullable
    private static UUID uuid(JsonNode payload, String field) {
        String value = payload.path(field).stringValue(null);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static BigDecimal money(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        if (node.isMissingNode() || node.isNull()) {
            // Left as a bare IllegalArgumentException (issue #1694 audit, category d): this is an
            // internal Kafka payload, never reachable from a controller, and both callers already
            // catch Exception broadly and log+skip a malformed event.
            throw new IllegalArgumentException("Payment event is missing required amount field: " + field);
        }
        return node.decimalValue();
    }

    private Instant instant(JsonNode payload, String field) {
        String value = payload.path(field).stringValue(null);
        return value == null ? Instant.now(clock) : Instant.parse(value);
    }
}
