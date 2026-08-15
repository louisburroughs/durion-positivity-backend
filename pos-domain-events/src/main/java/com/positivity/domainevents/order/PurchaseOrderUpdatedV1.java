package com.positivity.domainevents.order;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Current state of one purchase order, published on {@code order.events.v1} (ADR-0049 §3,
 * ADR-0044 §4).
 *
 * <h2>One fact, not a lifecycle</h2>
 *
 * There is deliberately no separate created / approved / revised / cancelled event. Every consumer
 * of this fact wants the order's <em>current</em> state rather than the story of how it got there:
 * availability-to-promise asks what is open now, replenishment asks whether stock is already on the
 * way, receiving asks what was ordered. Four event types would oblige each of them to reassemble
 * state from a stream they have no reason to follow, and would turn a missed event into a silently
 * wrong answer instead of a merely stale one. With a single full-state fact and the stale guard on
 * {@code aggregateVersion}, a consumer that misses an event is corrected by the next one.
 *
 * <h2>Who publishes it</h2>
 *
 * pos-order, once the purchase-order aggregate lives there (CAP-320). Until that move completes,
 * pos-inventory publishes it — on this topic, not its own, because the contract must not change
 * when the owner does. That is what lets the projection be built and proven against the running
 * implementation before the aggregate moves, rather than rewiring every reader at the same moment.
 *
 * <h2>Supply, not stock</h2>
 *
 * This fact says what has been ordered and what is still outstanding. It says nothing about what is
 * on a shelf: owned stock is pos-inventory's, computed from its ledger (ADR-0048). A consumer that
 * adds {@code openQuantity} to on-hand is describing future supply, and one that adds
 * {@code orderedQuantity} is counting received goods twice.
 *
 * @param purchaseOrderId      identity of the order; the event aggregate id
 * @param poNumber             human-readable order number
 * @param vendorId             vendor the order is placed with
 * @param status               lifecycle state: DRAFT, APPROVED, PARTIALLY_RECEIVED,
 *                             FULLY_RECEIVED, CLOSED or CANCELLED
 * @param shipToLocationId     receiving location; the site availability is attributed to
 * @param expectedDeliveryDate when the vendor is expected to deliver; null when undated, and an
 *                             undated order is deliberately excluded from horizon-bounded supply
 *                             rather than treated as arriving today
 * @param currency             ISO 4217 currency of the order's monetary figures
 * @param grandTotalMinor      order total in minor units, when priced
 * @param occurredAt           when this state was reached
 * @param lines                the order's lines; empty only for an order that has none
 */
public record PurchaseOrderUpdatedV1(
        @NonNull UUID purchaseOrderId,
        @NonNull String poNumber,
        @NonNull UUID vendorId,
        @NonNull String status,
        @Nullable UUID shipToLocationId,
        @Nullable LocalDate expectedDeliveryDate,
        @Nullable String currency,
        @Nullable Long grandTotalMinor,
        @NonNull Instant occurredAt,
        @NonNull List<PurchaseOrderLine> lines) {

    /** Event type of this payload on {@code order.events.v1}. */
    public static final String EVENT_TYPE = "purchaseorder.updated";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Statuses whose outstanding quantities count as incoming supply.
     *
     * <p>Declared here rather than in each consumer because getting it wrong is not a local
     * mistake: counting a {@code DRAFT} order would promise stock nobody has committed to buying,
     * and omitting {@code PARTIALLY_RECEIVED} would lose the remainder of an order already part
     * delivered.
     */
    public static final List<String> OPEN_SUPPLY_STATUSES = List.of("APPROVED", "PARTIALLY_RECEIVED");

    public PurchaseOrderUpdatedV1 {
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId must not be null");
        Objects.requireNonNull(poNumber, "poNumber must not be null");
        Objects.requireNonNull(vendorId, "vendorId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }

    /** Whether this order's outstanding quantities should be counted as incoming supply. */
    public boolean countsAsOpenSupply() {
        return OPEN_SUPPLY_STATUSES.contains(status);
    }
}
