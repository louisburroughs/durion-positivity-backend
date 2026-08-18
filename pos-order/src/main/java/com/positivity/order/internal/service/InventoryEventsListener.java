package com.positivity.order.internal.service;

import com.positivity.domainevents.inventory.GoodsReceiptLine;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.domainevents.inventory.InventoryAvailabilityUpdatedV1;
import com.positivity.domainevents.inventory.ReservationOutcomeV1;
import com.positivity.order.internal.entity.ExtInventoryAvailability;
import com.positivity.order.internal.entity.FulfillmentStatus;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.repository.ExtInventoryAvailabilityRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * This module's single consumer of {@code inventory.events.v1} (ADR-0044 §4/§6).
 *
 * <h2>Why one class for several unrelated facts</h2>
 *
 * {@code processed_events} is keyed by {@code eventId} alone, so a second listener on this topic
 * would race the first to insert the same id. One consumer per (module, topic) dispatching by
 * {@code eventType} is therefore the only shape that keeps the idempotency log correct — the same
 * shape pos-catalog uses. It handles goods receipts (CAP-320 #1334), availability snapshots and
 * reservation outcomes (both CAP #1315).
 *
 * <h2>Goods receipts: applying what pos-inventory received against a purchase order</h2>
 *
 * <h2>The order decides what a receipt means</h2>
 *
 * Receiving states what arrived. Whether that settles a line, and whether the order is now fully
 * received, are questions about the order's own lines — and this is the only module holding them.
 * Before the split receiving answered them itself by writing the order directly, which is what
 * made two modules writers of one aggregate.
 *
 * <h2>Applied exactly once</h2>
 *
 * Receipts are deltas: each says "this much more arrived". Applying one twice would decrement
 * twice and close an order that is still half outstanding, so the event-id guard here is doing
 * real work rather than merely avoiding wasted effort — unlike a full-state fact, this one is not
 * naturally idempotent.
 *
 * <h2>A receipt that cannot be attributed to a line</h2>
 *
 * Its value still comes off the outstanding balance, but no quantity moves. Guessing which line it
 * belonged to would settle the wrong one, and settling the wrong line is worse than leaving the
 * quantity outstanding: the balance shows something arrived, and the line stays visibly open.
 *
 * <h2>Availability: the replica behind the checkout gate</h2>
 *
 * Availability snapshots maintain {@code ext_inventory_availability}, which checkout reads to
 * decide whether a line is covered at the selling location or must be admitted as a backorder.
 * Snapshots are full state rather than deltas, so a stale one is discarded outright via the
 * strictly-below {@code aggregateVersion} guard instead of being applied out of order.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.order.kafka", name = "enabled", havingValue = "true")
public class InventoryEventsListener {

    /** Producing domain, per the repo-wide {@code processed_events} convention. */
    static final String OWNER = "inventory";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderFactPublisher purchaseOrderFactPublisher;
    private final ExtInventoryAvailabilityRepository extInventoryAvailabilityRepository;
    private final SalesOrderLineRepository salesOrderLineRepository;

    @KafkaListener(
            topics = "${pos.order.kafka.inventory-events-topic:inventory.events.v1}",
            groupId = "${pos.order.kafka.inventory-events-consumer-group:pos-order-inventory-events}")
    @Transactional
    public void onInventoryEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable inventory event", e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping inventory event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            switch (eventType == null ? "" : eventType) {
                case GoodsReceiptRecordedV1.EVENT_TYPE -> apply(envelope);
                case InventoryAvailabilityUpdatedV1.EVENT_TYPE -> applyAvailabilityUpdated(envelope);
                case ReservationOutcomeV1.EVENT_TYPE -> applyReservationOutcome(envelope);
                // Ignored types still fall through to the processed_events insert below: the
                // owner's reconciliation manifest counts every fact this module was offered.
                default -> log.debug("Ignoring inventory event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            // Rethrown so the container retries. Recording this as processed would leave goods on
            // the shelf that the order still believes are outstanding, and no later event would
            // ever correct it — the receipt has already happened.
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed inventory event eventId={}", eventId, e);
        }

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    /**
     * Resolves a checked-out line against what pos-inventory actually decided (CAP #1315).
     *
     * <h2>Why the line is relabelled rather than left as checkout found it</h2>
     *
     * Checkout labels each line from the availability replica, which trails the owning ledger.
     * This fact is the owner's own answer, read from its ledger, so where the two disagree this
     * one wins: a line checked out as {@code AVAILABLE} against a stale replica is corrected to
     * {@code BACKORDER}, and a line pessimistically marked {@code BACKORDER} — which is what an
     * absent replica row produces — is corrected back to {@code AVAILABLE}. Leaving the checkout
     * guess in place would make the order permanently disagree with inventory about a sale that
     * has already happened.
     *
     * <h2>Nothing is reversed</h2>
     *
     * An uncovered outcome does not undo the checkout. The sale is already made and the customer
     * has already paid; the shortfall is a promise to fulfil, tracked by the backorder the owner
     * opened and recorded here so the order can answer for it. (Work orders differ — a short part
     * there blocks the item — but that is pos-workorder's gate, not this one.)
     *
     * <h2>Facts for other modules' lines</h2>
     *
     * The topic carries every reservation outcome, including those for workorder lines. A fact
     * naming no sales-order line, or one this module does not hold, is not an error — it belongs
     * to someone else.
     */
    private void applyReservationOutcome(JsonNode envelope) {
        ReservationOutcomeV1 outcome = objectMapper.treeToValue(envelope.path("payload"), ReservationOutcomeV1.class);
        if (outcome.salesOrderLineId() == null) {
            return;
        }
        SalesOrderLine line =
                salesOrderLineRepository.findById(outcome.salesOrderLineId()).orElse(null);
        if (line == null) {
            log.debug(
                    "Reservation outcome {} names sales-order line {} which this module does not hold",
                    outcome.reservationId(),
                    outcome.salesOrderLineId());
            return;
        }

        FulfillmentStatus resolved = outcome.covered() ? FulfillmentStatus.AVAILABLE : FulfillmentStatus.BACKORDER;
        if (line.getFulfillmentStatus() != resolved) {
            log.info(
                    "Reservation outcome {} corrects line {} from {} to {}",
                    outcome.reservationId(),
                    line.getOrderLineId(),
                    line.getFulfillmentStatus(),
                    resolved);
        }
        line.setFulfillmentStatus(resolved);
        line.setReservationId(outcome.reservationId());
        line.setBackorderId(outcome.backorderId());
        salesOrderLineRepository.save(line);
    }

    /**
     * Availability is a snapshot, not a delta, so an out-of-order arrival is dropped rather than
     * applied: replaying an older full state would silently roll the replica backwards, and no
     * later event would correct it until that (sku, location) is touched again.
     */
    private void applyAvailabilityUpdated(JsonNode envelope) {
        InventoryAvailabilityUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), InventoryAvailabilityUpdatedV1.class);
        String rawAggregateId = envelope.path("aggregateId").stringValue(null);
        if (rawAggregateId == null || rawAggregateId.isBlank()) {
            log.warn("Skipping availability fact without aggregateId for sku {}", payload.stockItemId());
            return;
        }
        UUID aggregateId = UUID.fromString(rawAggregateId);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);

        ExtInventoryAvailability existing =
                extInventoryAvailabilityRepository.findById(aggregateId).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }

        extInventoryAvailabilityRepository.save(ExtInventoryAvailability.builder()
                .aggregateId(aggregateId)
                .stockItemId(payload.stockItemId())
                .locationId(payload.locationId())
                .onHandQuantity(payload.onHandQuantity())
                .allocatedQuantity(payload.allocatedQuantity())
                .availableToPromiseQuantity(payload.availableToPromiseQuantity())
                .unitOfMeasure(payload.unitOfMeasure())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.debug(
                "Updated ext_inventory_availability sku={} locationId={} atp={} version={}",
                payload.stockItemId(),
                payload.locationId(),
                payload.availableToPromiseQuantity(),
                aggregateVersion);
    }

    private void apply(JsonNode envelope) {
        GoodsReceiptRecordedV1 receipt =
                objectMapper.treeToValue(envelope.path("payload"), GoodsReceiptRecordedV1.class);

        PurchaseOrderEntity order =
                purchaseOrderRepository.findById(receipt.purchaseOrderId()).orElse(null);
        if (order == null) {
            // Receiving validated the order against its replica, so this means the receipt arrived
            // for an order this module has never held — a data problem, not a race worth retrying.
            log.warn(
                    "Goods receipt {} references unknown purchase order {}",
                    receipt.receiptId(),
                    receipt.purchaseOrderId());
            return;
        }

        Map<UUID, PurchaseOrderLineEntity> linesById = order.getLines().stream()
                .filter(line -> line.getLineId() != null)
                .collect(Collectors.toMap(PurchaseOrderLineEntity::getLineId, Function.identity(), (a, b) -> a));

        for (GoodsReceiptLine line : receipt.lines()) {
            if (line.poLineId() == null) {
                continue;
            }
            PurchaseOrderLineEntity orderLine = linesById.get(line.poLineId());
            if (orderLine == null) {
                log.warn("Goods receipt {} references unknown line {}", receipt.receiptId(), line.poLineId());
                continue;
            }
            BigDecimal open = orderLine.getOpenQuantityDecimal() == null
                    ? orderLine.getQuantityDecimal()
                    : orderLine.getOpenQuantityDecimal();
            BigDecimal next = (open == null ? BigDecimal.ZERO : open).subtract(line.quantityReceived());
            // Floored at zero: an over-receipt settles the line rather than making it negative,
            // which would otherwise read downstream as supply flowing the wrong way.
            orderLine.setOpenQuantityDecimal(next.signum() < 0 ? BigDecimal.ZERO : next);
        }

        long openBalance = order.getOpenBalanceMinor() == null ? 0L : order.getOpenBalanceMinor();
        order.setOpenBalanceMinor(Math.max(0L, openBalance - receipt.totalAccruedAmountMinor()));
        order.setStatus(nextStatus(order));
        order.setVersionNumber(order.getVersionNumber() == null ? 1 : order.getVersionNumber() + 1);

        PurchaseOrderEntity saved = purchaseOrderRepository.save(order);
        // The order's new state goes out as the ordinary fact, so availability-to-promise stops
        // counting what has arrived without needing to know a receipt happened.
        purchaseOrderFactPublisher.publish(saved, saved.getLines());

        log.debug(
                "Applied goods receipt {} to purchase order {}: status={} openBalanceMinor={}",
                receipt.receiptId(),
                saved.getPurchaseOrderId(),
                saved.getStatus(),
                saved.getOpenBalanceMinor());
    }

    /**
     * Fully received only when every line is settled.
     *
     * <p>Deliberately decided from the lines rather than from the balance reaching zero. A price
     * concession can zero the balance while goods are still owed, and an order closed on that basis
     * would drop its outstanding quantities out of supply while the vendor was still shipping them.
     */
    private static PurchaseOrderStatus nextStatus(PurchaseOrderEntity order) {
        boolean allSettled = order.getLines().stream()
                .map(line -> line.getOpenQuantityDecimal() == null
                        ? line.getQuantityDecimal()
                        : line.getOpenQuantityDecimal())
                .allMatch(open -> open == null || open.signum() <= 0);
        return allSettled ? PurchaseOrderStatus.FULLY_RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED;
    }
}
