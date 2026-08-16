package com.positivity.order.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.order.PurchaseOrderLine;
import com.positivity.domainevents.order.PurchaseOrderUpdatedV1;
import com.positivity.order.internal.config.OutboxEventWriter;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code purchaseorder.updated} on {@code order.events.v1} whenever a purchase order's
 * state changes (CAP-320 #1334, ADR-0049 §3).
 *
 * <h2>The same contract, now from its owner</h2>
 *
 * pos-inventory published this fact while it still held the aggregate (#1333), deliberately on
 * this topic rather than its own, so that moving the aggregate would not also move the contract.
 * That is what makes this story a deletion on the pos-inventory side rather than a rewiring of
 * every reader: consumers were already reading the fact they read now.
 *
 * <h2>Full state, every time</h2>
 *
 * The fact carries the order and all its lines as they now stand, not a delta. A consumer that
 * misses one is corrected by the next rather than left holding a gap it cannot detect — which
 * matters because the principal consumer computes availability-to-promise, and a quietly missing
 * line understates supply rather than failing loudly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseOrderFactPublisher {

    private static final String SOURCE = "pos-order";

    /**
     * Optional so the publisher is inert where the outbox is not wired. A deployment without
     * eventing still writes purchase orders; it simply publishes nothing.
     */
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    private final Clock clock;

    /**
     * Queues the order's current state for publication, in the caller's transaction.
     *
     * <p>{@code aggregateVersion} is the order's own version number, so a consumer's stale guard
     * compares like with like: two facts for one order are ordered by the version the order
     * carries, not by the clock of whichever instance emitted them.
     */
    public void publish(@NonNull PurchaseOrderEntity order, @NonNull List<PurchaseOrderLineEntity> lines) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }

        writer.publish(
                DomainTopics.events("order"),
                new DomainEventEnvelope<>(
                        UUIDv7Generator.generate(),
                        PurchaseOrderUpdatedV1.EVENT_TYPE,
                        PurchaseOrderUpdatedV1.SCHEMA_VERSION,
                        order.getPurchaseOrderId(),
                        versionOf(order),
                        Instant.now(clock),
                        SOURCE,
                        null,
                        order.getUpdatedBy(),
                        toFact(order, lines, Instant.now(clock))));

        log.debug(
                "Queued purchaseorder.updated for {} status={} lines={}",
                order.getPurchaseOrderId(),
                order.getStatus(),
                lines.size());
    }

    /**
     * Builds the published state of one order.
     *
     * <p>Package-private rather than private so tests can project an aggregate through the exact
     * mapping production uses. A test helper with its own copy of this mapping would keep passing
     * while the real one drifted.
     */
    static PurchaseOrderUpdatedV1 toFact(
            PurchaseOrderEntity order, List<PurchaseOrderLineEntity> lines, Instant occurredAt) {
        return new PurchaseOrderUpdatedV1(
                order.getPurchaseOrderId(),
                order.getPoNumber(),
                order.getVendorId(),
                order.getStatus().name(),
                order.getShipToLocationId(),
                order.getExpectedDeliveryDate(),
                order.getCurrency(),
                order.getGrandTotalMinor(),
                order.getOpenBalanceMinor(),
                occurredAt,
                lines.stream().map(PurchaseOrderFactPublisher::toFactLine).toList());
    }

    /**
     * The order's version number, or zero before one is assigned.
     *
     * <p>Zero is safe as a floor rather than a collision risk: an order with no version yet has not
     * been persisted, so no earlier fact for it can exist to be ordered against.
     */
    private static long versionOf(PurchaseOrderEntity order) {
        Integer version = order.getVersionNumber();
        return version == null ? 0L : version.longValue();
    }

    private static PurchaseOrderLine toFactLine(PurchaseOrderLineEntity line) {
        BigDecimal ordered = line.getQuantityDecimal() == null ? BigDecimal.ZERO : line.getQuantityDecimal();
        // Open quantity is null on a line that has never been received against; it means "all of it
        // is still outstanding", not "none of it is".
        BigDecimal open = line.getOpenQuantityDecimal() == null ? ordered : line.getOpenQuantityDecimal();
        return new PurchaseOrderLine(
                line.getLineId(),
                line.getLineNumber() == null ? 0 : line.getLineNumber(),
                line.getSkuId(),
                ordered,
                open,
                line.getUnitCostMinor(),
                line.getDescription());
    }
}
