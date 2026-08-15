package com.positivity.inventory.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.order.PurchaseOrderLine;
import com.positivity.domainevents.order.PurchaseOrderUpdatedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.entity.PurchaseOrderEntity;
import com.positivity.inventory.internal.entity.PurchaseOrderLineEntity;
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
 * state changes (CAP-320, ADR-0049 §3).
 *
 * <h2>Why pos-inventory publishes on the order domain's topic</h2>
 *
 * Because the aggregate is moving to pos-order and the contract must not move with it. Publishing on
 * {@code inventory.events.v1} today would mean changing the topic — and every consumer — at the same
 * moment the aggregate relocates, which is exactly the simultaneous rewiring this sequencing exists
 * to avoid. Emitting the eventual owner's contract from the current owner lets the projection be
 * built and proven against the running implementation, so the move becomes a deletion.
 *
 * <p>This is the one place in the module that knowingly produces on another domain's topic. It is
 * temporary by construction: when pos-order gains the aggregate, this class is deleted rather than
 * repointed.
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

    private static final String SOURCE = "pos-inventory";

    /**
     * Optional so the publisher is inert where the outbox is not wired — the same treatment
     * {@link InventoryFactPublisher} gives it. A deployment without eventing still writes purchase
     * orders; it simply publishes nothing.
     */
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    private final Clock clock;

    /**
     * Queues the order's current state for publication, in the caller's transaction.
     *
     * <p>{@code aggregateVersion} is the order's own optimistic-lock version, so a consumer's stale
     * guard compares like with like: two facts for one order are ordered by the version the database
     * assigned, not by the clock of whichever instance emitted them.
     */
    public void publish(@NonNull PurchaseOrderEntity order, @NonNull List<PurchaseOrderLineEntity> lines) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }

        PurchaseOrderUpdatedV1 payload = new PurchaseOrderUpdatedV1(
                order.getPurchaseOrderId(),
                order.getPoNumber(),
                order.getVendorId(),
                order.getStatus().name(),
                order.getShipToLocationId(),
                order.getExpectedDeliveryDate(),
                order.getCurrency(),
                order.getGrandTotalMinor(),
                Instant.now(clock),
                lines.stream().map(PurchaseOrderFactPublisher::toFactLine).toList());

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
                        payload));

        log.debug(
                "Queued purchaseorder.updated for {} status={} lines={}",
                order.getPurchaseOrderId(),
                order.getStatus(),
                lines.size());
    }

    /**
     * The order's optimistic-lock version, or zero before one is assigned.
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
