package com.positivity.order.internal.service;

import com.positivity.domainevents.inventory.GoodsReceiptLine;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.domainevents.inventory.VendorReturnRecordedV1;
import com.positivity.domainevents.inventory.InventoryAvailabilityUpdatedV1;
import com.positivity.domainevents.inventory.ReservationOutcomeV1;
import com.positivity.kafka.common.KafkaRails;
import com.positivity.order.internal.entity.ExtInventoryAvailability;
import com.positivity.order.internal.entity.FulfillmentStatus;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.repository.ExtInventoryAvailabilityRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
 * <h2>A receipt never reopens a cancelled or closed order (#2634)</h2>
 *
 * The receipt's status rule applies only while the order is being received: {@code APPROVED},
 * {@code PARTIALLY_RECEIVED} or {@code FULLY_RECEIVED}. Any other order keeps its status. A receipt
 * reaches a {@code CANCELLED} order when the cancellation and the receipt cross: receiving gates on
 * its replica, which trails this module, and the fact describes goods already on the shelf, so it
 * cannot be refused (ADR-0044 §4). Its quantities and value are still recorded and the order's
 * fact is republished with the status unchanged, so the replica's figures match while the order
 * stays out of incoming supply. Recomputing the status here would make a cancelled order
 * {@code PARTIALLY_RECEIVED}, which is open supply, and receiving would accept more against it.
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
 *
 * <h2>Transaction shape (#2146)</h2>
 *
 * <p>The listener method is not {@code @Transactional}; the handler and its
 * {@code processed_events} mark commit together in a {@code REQUIRES_NEW} transaction of their
 * own, and a permanent failure — even one thrown through a transactional repository or service —
 * rolls back only that transaction and is then recorded in a second one, rather than marking a
 * listener-wide transaction rollback-only, whose commit would throw
 * {@code UnexpectedRollbackException} and send the record through the container's retry and
 * dead-letter ladder with the mark rolled back each time. Transient database errors still
 * propagate for container retry. The mark commits with the work, not after it, because a goods
 * receipt is a delta: a mark lost after its receipt committed would let redelivery decrement the
 * order twice.
 */
@Slf4j
@Component
@KafkaRails
public class InventoryEventsListener {
    private static final String PAYLOAD = "payload";


    /** Producing domain, per the repo-wide {@code processed_events} convention. */
    static final String OWNER = "inventory";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderFactPublisher purchaseOrderFactPublisher;
    private final ExtInventoryAvailabilityRepository extInventoryAvailabilityRepository;
    private final SalesOrderLineRepository salesOrderLineRepository;

    /** The event's handler work and its processed mark, in a transaction of their own; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public InventoryEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderFactPublisher purchaseOrderFactPublisher,
            ExtInventoryAvailabilityRepository extInventoryAvailabilityRepository,
            SalesOrderLineRepository salesOrderLineRepository,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderFactPublisher = purchaseOrderFactPublisher;
        this.extInventoryAvailabilityRepository = extInventoryAvailabilityRepository;
        this.salesOrderLineRepository = salesOrderLineRepository;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.order.kafka.inventory-events-topic:inventory.events.v1}",
            groupId = "${pos.order.kafka.inventory-events-consumer-group:pos-order-inventory-events}")
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
            handlerTransaction.executeWithoutResult(_ -> {
                switch (eventType == null ? "" : eventType) {
                    case GoodsReceiptRecordedV1.EVENT_TYPE -> apply(envelope);
                    case VendorReturnRecordedV1.EVENT_TYPE -> applyVendorReturn(envelope);
                    case InventoryAvailabilityUpdatedV1.EVENT_TYPE -> applyAvailabilityUpdated(envelope);
                    case ReservationOutcomeV1.EVENT_TYPE -> applyReservationOutcome(envelope);
                    // Ignored types still fall through to the processed_events insert below: the
                    // owner's reconciliation manifest counts every fact this module was offered.
                    default -> log.debug("Ignoring inventory event type={} eventId={}", eventType, eventId);
                }
                markProcessed(eventId);
            });
        } catch (Exception e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                // Rethrown so the container retries. Recording this as processed would leave goods on
                // the shelf that the order still believes are outstanding, and no later event would
                // ever correct it — the receipt has already happened.
                throw e;
            }
            log.warn("Skipping malformed inventory event eventId={}", eventId, e);
            handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
        }
    }

    private void markProcessed(String eventId) {
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
        ReservationOutcomeV1 outcome = objectMapper.treeToValue(envelope.path(PAYLOAD), ReservationOutcomeV1.class);
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
                objectMapper.treeToValue(envelope.path(PAYLOAD), InventoryAvailabilityUpdatedV1.class);
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
        GoodsReceiptRecordedV1 receipt = objectMapper.treeToValue(envelope.path(PAYLOAD), GoodsReceiptRecordedV1.class);

        // Row-locked, as revise reads it (#2632): a receipt and a revision of the same order take
        // turns, so neither writes over the other from a stale read.
        PurchaseOrderEntity order = purchaseOrderRepository
                .findByIdForUpdate(receipt.purchaseOrderId())
                .orElse(null);
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

        // #2635: a receipt adds to the line's received figures; open, balance and status are derived from them.
        // A line without a poLineId, or naming a line the order does not hold, moves only the balance (as before).
        for (GoodsReceiptLine line : receipt.lines()) {
            PurchaseOrderLineEntity orderLine = line.poLineId() == null ? null : linesById.get(line.poLineId());
            if (orderLine == null) {
                if (line.poLineId() != null) {
                    log.warn("Goods receipt {} references unknown line {}", receipt.receiptId(), line.poLineId());
                }
                order.setUnallocatedAccruedMinor(PurchaseOrderLineFigures.zeroIfNull(order.getUnallocatedAccruedMinor())
                        + line.accruedAmountMinor());
                continue;
            }
            // Over-receipt: received may exceed ordered; the derived open quantity floors at zero, so the line
            // settles rather than reading downstream as supply flowing the wrong way.
            orderLine.setReceivedQuantity(PurchaseOrderLineFigures.zeroIfNull(orderLine.getReceivedQuantity())
                    .add(line.quantityReceived()));
            orderLine.setReceivedAccruedMinor(PurchaseOrderLineFigures.zeroIfNull(orderLine.getReceivedAccruedMinor())
                    + line.accruedAmountMinor());
        }

        if (!PurchaseOrderLineFigures.RECEIVING_STATUSES.contains(order.getStatus())) {
            log.warn(
                    "Goods receipt {} applied to purchase order {} in status {}: quantities and balance recorded, "
                            + "status kept",
                    receipt.receiptId(),
                    order.getPurchaseOrderId(),
                    order.getStatus());
        }
        PurchaseOrderLineFigures.refresh(order);
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
     * A vendor return or a receipt correction (CAP:550 #2635, Order ruling on #2598). Both take the returned units
     * off the line's received figures. A {@code RECEIPT_CORRECTION} then reopens the line from the net received
     * quantity (the goods are still owed) and republishes the order. A {@code RETURN_TO_VENDOR} defaults to credit:
     * whatever the subtraction reopened is put on the line's returned figures, so open, balance and status are
     * unchanged and nothing is published; a replacement is a per-return choice made by reopening the line.
     * {@code CANCELLED} and {@code CLOSED} orders keep their status; the figures still move (#2634).
     */
    private void applyVendorReturn(JsonNode envelope) {
        VendorReturnRecordedV1 vendorReturn =
                objectMapper.treeToValue(envelope.path(PAYLOAD), VendorReturnRecordedV1.class);
        boolean correction;
        switch (vendorReturn.returnKind()) {
            case VendorReturnRecordedV1.RECEIPT_CORRECTION -> correction = true;
            case VendorReturnRecordedV1.RETURN_TO_VENDOR -> correction = false;
            default -> {
                log.warn(
                        "Vendor return {} of purchase order {} has unknown returnKind {}; not applied",
                        vendorReturn.vendorReturnId(),
                        vendorReturn.purchaseOrderId(),
                        vendorReturn.returnKind());
                return;
            }
        }
        PurchaseOrderEntity order = purchaseOrderRepository
                .findByIdForUpdate(vendorReturn.purchaseOrderId())
                .orElse(null);
        if (order == null) {
            log.warn(
                    "Vendor return {} references unknown purchase order {}",
                    vendorReturn.vendorReturnId(),
                    vendorReturn.purchaseOrderId());
            return;
        }
        String currency = order.getCurrency() != null ? order.getCurrency() : vendorReturn.currencyCode();
        Map<UUID, PurchaseOrderLineEntity> linesById = order.getLines().stream()
                .filter(line -> line.getLineId() != null)
                .collect(Collectors.toMap(PurchaseOrderLineEntity::getLineId, Function.identity(), (a, b) -> a));
        boolean anyLineMoved = false;
        for (VendorReturnRecordedV1.VendorReturnLine line : vendorReturn.lines()) {
            long accrued = PurchaseOrderLineFigures.toMinor(line.accruedAmount(), currency);
            PurchaseOrderLineEntity orderLine = line.poLineId() == null ? null : linesById.get(line.poLineId());
            if (orderLine == null) {
                if (line.poLineId() != null) {
                    log.warn(
                            "Vendor return {} references unknown line {}", vendorReturn.vendorReturnId(), line.poLineId());
                }
                // The receipt moved only the balance; its return gives that back, never below zero.
                order.setUnallocatedAccruedMinor(Math.max(
                        0L, PurchaseOrderLineFigures.zeroIfNull(order.getUnallocatedAccruedMinor()) - accrued));
                continue;
            }
            BigDecimal openBefore = PurchaseOrderLineFigures.open(orderLine);
            BigDecimal received = PurchaseOrderLineFigures.zeroIfNull(orderLine.getReceivedQuantity())
                    .subtract(line.quantityReturned());
            orderLine.setReceivedQuantity(received.signum() < 0 ? BigDecimal.ZERO : received);
            orderLine.setReceivedAccruedMinor(Math.max(
                    0L, PurchaseOrderLineFigures.zeroIfNull(orderLine.getReceivedAccruedMinor()) - accrued));
            if (!correction) {
                BigDecimal reopened = PurchaseOrderLineFigures.open(orderLine).subtract(openBefore);
                if (reopened.signum() > 0) {
                    orderLine.setReturnedQuantity(
                            PurchaseOrderLineFigures.zeroIfNull(orderLine.getReturnedQuantity()).add(reopened));
                }
                orderLine.setReturnedValueMinor(
                        PurchaseOrderLineFigures.zeroIfNull(orderLine.getReturnedValueMinor()) + accrued);
            }
            anyLineMoved = true;
        }
        if (!PurchaseOrderLineFigures.RECEIVING_STATUSES.contains(order.getStatus())) {
            log.warn(
                    "Vendor return {} applied to purchase order {} in status {}: figures recorded, status kept",
                    vendorReturn.vendorReturnId(),
                    order.getPurchaseOrderId(),
                    order.getStatus());
        }
        PurchaseOrderLineFigures.refresh(order);
        if (correction) {
            order.setVersionNumber(order.getVersionNumber() == null ? 1 : order.getVersionNumber() + 1);
            PurchaseOrderEntity saved = purchaseOrderRepository.save(order);
            purchaseOrderFactPublisher.publish(saved, saved.getLines());
        } else {
            // Credit: no published figure changed, so no fact. The figures are still stored.
            purchaseOrderRepository.save(order);
        }
        log.debug(
                "Applied vendor return {} ({}) to purchase order {}: status={} openBalanceMinor={} linesMoved={}",
                vendorReturn.vendorReturnId(),
                vendorReturn.returnKind(),
                order.getPurchaseOrderId(),
                order.getStatus(),
                order.getOpenBalanceMinor(),
                anyLineMoved);
    }
}
