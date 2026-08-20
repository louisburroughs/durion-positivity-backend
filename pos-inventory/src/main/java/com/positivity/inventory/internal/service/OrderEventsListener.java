package com.positivity.inventory.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.inventory.CounterSaleConsumptionFailedV1;
import com.positivity.domainevents.order.OrderCompletedV1;
import com.positivity.domainevents.order.OrderReturnedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.ProcessedEvent;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Counter-sale stock consumption (order parity story H2, #1079; order-spec R8.2, R7.5): consumes
 * {@code order.order.completed} and posts a {@code GOODS_ISSUE} ledger movement per fulfillable
 * line. The producer already excludes WORKORDER-sourced lines from the fulfillable set — the
 * consumer asserts the rule defensively. A line that cannot post (insufficient stock — the
 * fulfillment-pending case — or bad data) raises
 * {@code inventory.counter-sale.consumption-failed} and never affects the completed sale (the
 * Odoo failed-pickings analog).
 *
 * <p>Idempotent via {@code processed_events} in the posting transaction; transient DB errors are
 * rethrown for container retry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.inventory.kafka", name = "enabled", havingValue = "true")
public class OrderEventsListener {

    /** Producing domain, per the repo-wide processed_events convention. */
    static final String OWNER = "order";

    private static final String COUNTER_SALE_REASON = "COUNTER_SALE";
    private static final String COUNTER_RETURN_REASON = "COUNTER_RETURN";
    private static final String RESTOCK_CONDITION = "RESTOCK";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final CounterSaleIssuePoster counterSaleIssuePoster;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    @Value("${pos.inventory.kafka.events-topic:inventory.events.v1}")
    private String eventsTopic;

    @KafkaListener(
            topics = "${pos.inventory.kafka.order-events-topic:order.events.v1}",
            groupId = "${pos.inventory.kafka.order-events-consumer-group:pos-inventory-order-events}")
    @Transactional
    public void onOrderEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable order event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping order event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (OrderCompletedV1.EVENT_TYPE.equals(eventType)) {
                applyOrderCompleted(envelope.path("payload"));
            } else if (OrderReturnedV1.EVENT_TYPE.equals(eventType)) {
                applyOrderReturned(envelope.path("payload"));
            } else {
                log.debug("Ignoring order event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed order event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyOrderCompleted(JsonNode payload) {
        UUID orderId = uuid(payload, "orderId");
        String orderNumber = payload.path("orderNumber").stringValue(null);
        UUID locationId = uuid(payload, "locationId");
        for (JsonNode line : payload.path("lines")) {
            if (!line.path("fulfillable").booleanValue(false)) {
                continue;
            }
            // Spec R7.5 defensive assert: the source document owns WORKORDER-line stock.
            if ("WORKORDER".equals(line.path("sourceType").stringValue(null))) {
                log.warn(
                        "Order {} line {} is WORKORDER-sourced but flagged fulfillable; skipping (spec R7.5)",
                        orderId,
                        line.path("orderLineId").stringValue(null));
                continue;
            }
            UUID orderLineId = uuid(line, "orderLineId");
            String sku = line.path("sku").stringValue(null);
            int quantity = line.path("quantity").intValue(0);
            if (sku == null || quantity <= 0 || orderLineId == null) {
                continue;
            }
            try {
                counterSaleIssuePoster.post(InventoryLedgerEntry.builder()
                        .stockItemId(sku)
                        .eventType(InventoryLedgerEventType.GOODS_ISSUE)
                        // Sales-order demand stays integral by decision (ADR-0055 leaves
                        // SalesOrderLine.quantity an int), so this widens at the boundary rather
                        // than narrowing anything.
                        .changeInQuantity(BigDecimal.valueOf(quantity).negate())
                        .quantityAfter(BigDecimal.ZERO)
                        .locationId(locationId)
                        .reasonCode(COUNTER_SALE_REASON)
                        .sourceTransactionId(orderLineId.toString())
                        .transactionUserId("pos-order")
                        .notes("Counter sale, order " + (orderNumber != null ? orderNumber : orderId))
                        .build());
            } catch (TransientDataAccessException e) {
                throw e;
            } catch (Exception e) {
                // The sale already happened; stock truth reconciles by hand (spec R8.2).
                log.warn("Counter-sale consumption failed for order {} sku {}: {}", orderId, sku, e.getMessage());
                publishConsumptionFailed(orderId, orderNumber, orderLineId, sku, quantity, locationId, e.getMessage());
            }
        }
    }

    /**
     * Return restock (order parity story F2, #1087): consumes {@code order.order.returned} and posts
     * a {@code RETURN_TO_STOCK} movement per RESTOCK-condition line. SCRAP / WARRANTY lines skip
     * inventory (no on-hand change). Per-line idempotency keys on the return + original line so two
     * returns of the same sold line never collide.
     */
    private void applyOrderReturned(JsonNode payload) {
        UUID returnOrderId = uuid(payload, "returnOrderId");
        UUID originalOrderId = uuid(payload, "originalOrderId");
        UUID locationId = uuid(payload, "locationId");
        for (JsonNode line : payload.path("lines")) {
            if (!RESTOCK_CONDITION.equals(line.path("condition").stringValue(null))) {
                continue; // SCRAP / WARRANTY do not return to sellable stock
            }
            UUID originalOrderLineId = uuid(line, "originalOrderLineId");
            String sku = line.path("sku").stringValue(null);
            int quantity = line.path("quantity").intValue(0);
            if (sku == null || quantity <= 0 || originalOrderLineId == null || returnOrderId == null) {
                continue;
            }
            String sourceTransactionId = returnOrderId + ":" + originalOrderLineId;
            try {
                counterSaleIssuePoster.post(InventoryLedgerEntry.builder()
                        .stockItemId(sku)
                        .eventType(InventoryLedgerEventType.RETURN_TO_STOCK)
                        .changeInQuantity(BigDecimal.valueOf(quantity))
                        .quantityAfter(BigDecimal.ZERO)
                        .locationId(locationId)
                        .reasonCode(COUNTER_RETURN_REASON)
                        .sourceTransactionId(sourceTransactionId)
                        .transactionUserId("pos-order")
                        .notes("Return to stock, return " + returnOrderId + " (order " + originalOrderId + ")")
                        .build());
            } catch (TransientDataAccessException e) {
                throw e;
            } catch (Exception e) {
                // The refund already happened; stock truth reconciles by hand, as for consumption.
                log.warn("Return restock failed for return {} sku {}: {}", returnOrderId, sku, e.getMessage());
            }
        }
    }

    private void publishConsumptionFailed(
            UUID orderId,
            @Nullable String orderNumber,
            UUID orderLineId,
            String sku,
            int quantity,
            @Nullable UUID locationId,
            String reason) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        CounterSaleConsumptionFailedV1 alert = new CounterSaleConsumptionFailedV1(
                orderId,
                orderNumber,
                orderLineId,
                sku,
                quantity,
                locationId,
                reason == null ? "unknown" : reason,
                Instant.now(clock));
        writer.publish(
                eventsTopic,
                DomainEventEnvelope.of(
                        CounterSaleConsumptionFailedV1.EVENT_TYPE,
                        CounterSaleConsumptionFailedV1.SCHEMA_VERSION,
                        orderId,
                        Instant.now(clock).toEpochMilli(),
                        "pos-inventory",
                        null,
                        null,
                        alert,
                        clock));
    }

    @Nullable
    private static UUID uuid(JsonNode node, String field) {
        String value = node.path(field).stringValue(null);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
