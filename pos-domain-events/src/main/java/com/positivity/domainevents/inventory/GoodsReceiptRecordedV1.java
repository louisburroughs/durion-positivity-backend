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
 * <h2>Currency, units and nulls (CAP:550 S41 #2602, #2598)</h2>
 *
 * Every amount on the fact — {@code totalAccruedAmountMinor} and each line's {@code accruedAmountMinor}
 * and {@code inventoryValueMinor} — is in whole minor units of {@code currencyCode}, the purchase
 * order's document currency as pos-inventory's order replica states it (ADR-0067 R-1, R-5). The code
 * is never defaulted (R-2): it is null only when the order states none, and a consumer must then
 * treat the amounts as having no currency (pos-accounting holds such a fact, PC-9). Each line is
 * rounded HALF_UP to whole minor units before the lines are summed (ADR-0067 OP-11, PC-6), so
 * {@code totalAccruedAmountMinor} is always the sum of the line accruals.
 *
 * <p>{@code currencyCode} and the per-line cost fields were added within v1 (ADR-0044 §3): they are
 * boxed, so a fact published before they existed reads them as null.
 *
 * @param receiptId               identity of the goods receipt; not the envelope aggregate id, which is
 *                                {@code purchaseOrderId} so every receipt of one order is applied in order
 * @param receiptNumber           human-readable receipt number
 * @param purchaseOrderId         the order received against
 * @param locationId              where the goods were received
 * @param totalAccruedAmountMinor value of this receipt in minor units of {@code currencyCode},
 *                                deducted from the order's outstanding balance; the sum of the
 *                                lines' {@code accruedAmountMinor}
 * @param occurredAt              when the receipt was recorded
 * @param lines                   what arrived, per purchase-order line
 * @param currencyCode            ISO 4217 code of every amount on the fact: the purchase order's
 *                                document currency; null only when the order states none, or on a
 *                                fact published before the field existed
 */
public record GoodsReceiptRecordedV1(
        @NonNull UUID receiptId,
        @Nullable String receiptNumber,
        @NonNull UUID purchaseOrderId,
        @Nullable UUID locationId,
        long totalAccruedAmountMinor,
        @NonNull Instant occurredAt,
        @NonNull List<GoodsReceiptLine> lines,
        @Nullable String currencyCode) {

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
