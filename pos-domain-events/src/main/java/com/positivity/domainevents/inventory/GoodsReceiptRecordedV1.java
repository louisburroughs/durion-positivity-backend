package com.positivity.domainevents.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What physically arrived against a purchase order, published on {@code inventory.events.v1}
 * (CAP-320 #1334, ADR-0044 §4, ADR-0049 §3).
 *
 * <h2>Why receiving reports rather than writes</h2>
 *
 * pos-inventory owns what arrived: it inspects, counts, posts to the ledger and captures lots.
 * pos-order owns what was ordered and what is still outstanding. Before the split, receiving wrote
 * the order's open quantities directly, which made two modules writers of one aggregate. This fact
 * is the seam: receiving states what it received, and the order decides what that means for its own
 * outstanding quantities and status.
 *
 * <p>That ordering matters for a subtle reason. Whether an order is now {@code FULLY_RECEIVED} is
 * a question about the order — every line settled, nothing outstanding — and the module holding
 * the lines is the only one that can answer it without guessing.
 *
 * <h2>Applied once</h2>
 *
 * Receipts are cumulative, not absolute: each one says "this much more arrived", so applying the
 * same one twice would decrement twice and close an order that is still half outstanding. The
 * consumer guards on {@code eventId} for that reason — unlike {@code purchaseorder.updated}, which
 * carries full state and is naturally idempotent, this fact is a delta and has to be.
 *
 * @param receiptId               identity of the goods receipt; the event aggregate id
 * @param receiptNumber           human-readable receipt number
 * @param purchaseOrderId         the order received against
 * @param locationId              where the goods were received
 * @param totalAccruedAmountMinor value of this receipt in minor units, deducted from the order's
 *                                outstanding balance
 * @param occurredAt              when the receipt was recorded
 * @param lines                   what arrived, per purchase-order line
 */
public record GoodsReceiptRecordedV1(
        @NonNull UUID receiptId,
        @Nullable String receiptNumber,
        @NonNull UUID purchaseOrderId,
        @Nullable UUID locationId,
        long totalAccruedAmountMinor,
        @NonNull Instant occurredAt,
        @NonNull List<GoodsReceiptLine> lines) {

    /** Event type of this payload on {@code inventory.events.v1}. */
    public static final String EVENT_TYPE = "goodsreceipt.recorded";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    public GoodsReceiptRecordedV1 {
        Objects.requireNonNull(receiptId, "receiptId must not be null");
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }
}
