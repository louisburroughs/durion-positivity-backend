package com.positivity.inventory.internal.service;

import com.positivity.domainevents.order.PurchaseOrderLine;
import com.positivity.domainevents.order.PurchaseOrderUpdatedV1;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderLineReplica;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderReceipt;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderReplica;
import com.positivity.inventory.internal.entity.ProcessedEvent;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderReceiptRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies {@code purchaseorder.updated} into the {@code ext_purchase_order} projection
 * (ADR-0044 R3/§4, CAP-320 #1333).
 *
 * <h2>What depends on this being right</h2>
 *
 * The projection is the supply side of availability-to-promise. A row that is missing or behind does
 * not merely stale a screen — it lowers the incoming figure a customer is quoted against, which is
 * wrong in the direction that promises stock nobody ordered. That is why this consumer keeps an
 * applied-version log alongside the ordinary idempotency guard: staleness has to be answerable, not
 * inferred from a number looking smaller than expected.
 *
 * <h2>Full state replaces, it does not merge</h2>
 *
 * Each fact carries the order and all its lines as they now stand, so applying one replaces the
 * projected lines outright. Merging would leave a line the owner has deleted sitting in the replica,
 * still counted as incoming supply, with nothing to reveal it — the failure a delta contract makes
 * easy and a full-state contract makes impossible.
 *
 * <h2>Three failures, three answers</h2>
 *
 * <ul>
 *   <li><strong>Redelivery</strong> — the event-id guard makes it a no-op.
 *   <li><strong>Out-of-order arrival</strong> — the stale guard on {@code aggregateVersion} skips a
 *       fact older than what is already applied, so a slow retry cannot resurrect a superseded state.
 *   <li><strong>Transient database trouble</strong> — rethrown so the container retries. Recording
 *       the event as processed here would drop an order out of supply permanently.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.inventory.kafka", name = "enabled", havingValue = "true")
public class PurchaseOrderProjectionListener {

    /** Producing domain, per the repo-wide {@code processed_events} convention. */
    static final String OWNER = "order";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtPurchaseOrderRepository orderRepository;
    private final ExtPurchaseOrderLineRepository lineRepository;
    private final ExtPurchaseOrderReceiptRepository receiptRepository;

    @KafkaListener(
            topics = "${pos.inventory.kafka.order-events-topic:order.events.v1}",
            groupId = "${pos.inventory.kafka.order-events-consumer-group:pos-inventory-order-events}")
    @Transactional
    public void onOrderEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable order event", e);
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
            if (PurchaseOrderUpdatedV1.EVENT_TYPE.equals(eventType)) {
                apply(envelope);
            } else {
                log.debug("Ignoring order event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            // Rethrown so the container retries. Recording this as processed would leave the order
            // out of the projection permanently, and availability-to-promise would keep quoting a
            // supply figure short by that order with nothing to show why.
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

    private void apply(JsonNode envelope) {
        PurchaseOrderUpdatedV1 fact = objectMapper.treeToValue(envelope.path("payload"), PurchaseOrderUpdatedV1.class);
        long version = envelope.path("aggregateVersion").longValue(0);
        UUID orderId = fact.purchaseOrderId();

        ExtPurchaseOrderReplica existing = orderRepository.findById(orderId).orElse(null);
        if (existing != null && existing.getAggregateVersion() > version) {
            log.debug(
                    "Skipping stale purchase-order fact for {}: have v{}, event v{}",
                    orderId,
                    existing.getAggregateVersion(),
                    version);
            return;
        }

        orderRepository.save(ExtPurchaseOrderReplica.builder()
                .purchaseOrderId(orderId)
                .poNumber(fact.poNumber())
                .vendorId(fact.vendorId())
                .status(fact.status())
                .shipToLocationId(fact.shipToLocationId())
                // Carried through exactly: an undated order stays undated, because the
                // horizon-bounded supply query excludes it rather than treating it as arriving today.
                .expectedDeliveryDate(fact.expectedDeliveryDate())
                .currency(fact.currency())
                .grandTotalMinor(fact.grandTotalMinor())
                .aggregateVersion(version)
                .occurredAt(fact.occurredAt())
                .build());

        // Replace rather than merge. A line the owner removed must disappear here too, or it keeps
        // counting as incoming supply that nobody will deliver.
        lineRepository.deleteByPurchaseOrderId(orderId);
        for (PurchaseOrderLine line : fact.lines()) {
            lineRepository.save(toReplica(orderId, line));
        }

        receiptRepository.save(ExtPurchaseOrderReceipt.builder()
                .purchaseOrderId(orderId)
                .appliedVersion(version)
                .appliedAt(Instant.now(clock))
                .build());

        log.debug(
                "Applied purchase-order fact for {} v{} ({} lines)",
                orderId,
                version,
                fact.lines().size());
    }

    private static ExtPurchaseOrderLineReplica toReplica(UUID orderId, PurchaseOrderLine line) {
        return ExtPurchaseOrderLineReplica.builder()
                .lineId(line.lineId())
                .purchaseOrderId(orderId)
                .lineNumber(line.lineNumber())
                .skuId(line.skuId())
                .orderedQuantity(line.orderedQuantity())
                .openQuantity(line.openQuantity())
                .unitCostMinor(line.unitCostMinor())
                .description(line.description())
                .build();
    }

    /** Lines currently projected for one order; used by the completeness checks and by tests. */
    @NonNull
    public List<ExtPurchaseOrderLineReplica> projectedLines(@NonNull UUID purchaseOrderId) {
        return lineRepository.findByPurchaseOrderId(purchaseOrderId);
    }
}
