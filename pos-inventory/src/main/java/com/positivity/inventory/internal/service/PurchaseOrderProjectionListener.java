package com.positivity.inventory.internal.service;

import com.positivity.domainevents.inventory.ExpectedSupplyDroppedV1;
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
    private final InventoryFactPublisher inventoryFactPublisher;

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
                .openBalanceMinor(fact.openBalanceMinor())
                .aggregateVersion(version)
                .occurredAt(fact.occurredAt())
                .build());

        // Before the lines are replaced, work out what supply this transition removes. The
        // quantities that disappear are the ones currently projected, so this has to happen while
        // they are still here.
        emitExpectedSupplyDroppedIfLeavingSupply(existing, fact);

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

    /**
     * Emits one {@link ExpectedSupplyDroppedV1} per SKU when an order leaves the open-supply
     * statuses (odoo-parity A4, #1028; CAP-320 #1334).
     *
     * <h2>Why this is derived here rather than published by the order</h2>
     *
     * Expected supply is an inventory concept: it is inventory that decides what counts as
     * incoming, and inventory that consumes the consequence. pos-order publishing it would mean
     * one domain emitting another's fact about a quantity it does not compute. The projection
     * already holds exactly what is needed — the open quantities as they stood before this
     * transition — so the same information is available without crossing that line.
     *
     * <p>An order that never entered supply (a draft that was cancelled) drops nothing, because
     * nothing was ever counted.
     */
    private void emitExpectedSupplyDroppedIfLeavingSupply(
            @org.jspecify.annotations.Nullable ExtPurchaseOrderReplica existing, PurchaseOrderUpdatedV1 fact) {
        if (existing == null) {
            return;
        }
        boolean wasCountedAsSupply = PurchaseOrderUpdatedV1.OPEN_SUPPLY_STATUSES.contains(existing.getStatus());
        if (!wasCountedAsSupply || fact.countsAsOpenSupply()) {
            return;
        }

        Map<UUID, BigDecimal> openBySku = new LinkedHashMap<>();
        for (ExtPurchaseOrderLineReplica line : lineRepository.findByPurchaseOrderId(existing.getPurchaseOrderId())) {
            if (line.getSkuId() == null
                    || line.getOpenQuantity() == null
                    || line.getOpenQuantity().signum() <= 0) {
                continue;
            }
            openBySku.merge(line.getSkuId(), line.getOpenQuantity(), BigDecimal::add);
        }
        if (openBySku.isEmpty()) {
            return;
        }

        String reason = "CANCELLED".equals(fact.status())
                ? ExpectedSupplyDroppedV1.REASON_CANCELLED
                : ExpectedSupplyDroppedV1.REASON_CLOSED;
        Instant occurredAt = Instant.now(clock);
        openBySku.forEach(
                (skuId, dropped) -> inventoryFactPublisher.recordExpectedSupplyDropped(new ExpectedSupplyDroppedV1(
                        skuId.toString(),
                        existing.getShipToLocationId(),
                        dropped,
                        existing.getPurchaseOrderId(),
                        reason,
                        occurredAt)));

        log.debug(
                "Expected supply dropped for order {} ({} SKUs, reason {})",
                existing.getPurchaseOrderId(),
                openBySku.size(),
                reason);
    }

    /** Lines currently projected for one order; used by the completeness checks and by tests. */
    @NonNull
    public List<ExtPurchaseOrderLineReplica> projectedLines(@NonNull UUID purchaseOrderId) {
        return lineRepository.findByPurchaseOrderId(purchaseOrderId);
    }
}
