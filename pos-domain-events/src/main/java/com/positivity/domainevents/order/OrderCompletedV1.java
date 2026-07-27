package com.positivity.domainevents.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a sales order completed — paid in full or accepted on account (order parity plan story D2,
 * issue #1073; spec R12.1, resolved Q9).
 *
 * <p>Published by pos-order on {@code order.events.v1} with
 * {@code eventType = "order.order.completed"} when the order reaches COMPLETED. The payload is a
 * rich integration contract sized for downstream autonomy — consumers must not need a synchronous
 * re-fetch for core processing — while staying PII-minimal: customer and vehicle ride as IDs only.
 *
 * <p>Consumers: pos-inventory posts counter-sale stock consumption for lines with
 * {@code fulfillable = true} (WORKORDER-sourced lines are excluded by the producer per spec R7.5 —
 * the source document owns its stock); pos-workorder marks workorders settled when
 * {@code workorderId} is present; pos-accounting posting rules; pos-customer promotion redemption
 * and vehicle service history.
 *
 * @param orderId sales order identifier (also the envelope aggregateId)
 * @param orderNumber human-facing order number
 * @param locationId shop location
 * @param workorderId settled workorder, when the order fronted a workorder settlement
 * @param sessionId register session the order was tendered under (null until story G1)
 * @param customerId customer party id (ID only — no embedded PII)
 * @param vehicleId vehicle id (ID only)
 * @param clerkId clerk who owned the order
 * @param terminalId terminal the order was tendered at
 * @param currencyCode ISO-4217, USD platform-wide
 * @param subtotal Σ line subtotals (post-line-discount, pre-order-discount, pre-tax)
 * @param discountTotal Σ line discounts + allocated order discount
 * @param taxTotal pos-tax authoritative total
 * @param grandTotal subtotal − order discount + taxTotal
 * @param lines completed line items
 * @param tenders settlement summary by method (from the order's payment records)
 * @param completedAt when the order reached COMPLETED
 */
public record OrderCompletedV1(
        @NonNull UUID orderId,
        @Nullable String orderNumber,
        @Nullable UUID locationId,
        @Nullable UUID workorderId,
        @Nullable UUID sessionId,
        @Nullable UUID customerId,
        @Nullable UUID vehicleId,
        @NonNull String clerkId,
        @NonNull String terminalId,
        @NonNull String currencyCode,
        @NonNull BigDecimal subtotal,
        @NonNull BigDecimal discountTotal,
        @NonNull BigDecimal taxTotal,
        @NonNull BigDecimal grandTotal,
        @NonNull List<Line> lines,
        @NonNull List<Tender> tenders,
        @NonNull Instant completedAt) {

    public static final String EVENT_TYPE = "order.order.completed";
    public static final int SCHEMA_VERSION = 1;

    /**
     * One completed order line.
     *
     * @param orderLineId line identifier
     * @param sku item SKU
     * @param description description as sold (denormalized at pricing time)
     * @param quantity units
     * @param unitPrice applied unit price
     * @param discountAmount line discount + order-discount allocation
     * @param lineSubtotal extended post-line-discount amount
     * @param taxAmount pos-tax line amount
     * @param lineTotal lineSubtotal − allocation + tax
     * @param priceSource PRICING_SERVICE / MANUAL / CACHE provenance
     * @param sourceType ESTIMATE/WORKORDER when imported from a source document
     * @param sourceId source document id
     * @param sourceLineId source document line id
     * @param returnable explicit returnability flag for settled source lines (null until story E1)
     * @param serialNumbers captured serials for tracked products (empty until story H3)
     * @param fulfillable true when pos-inventory should post counter-sale consumption for this
     *     line; always false for WORKORDER-sourced lines (spec R7.5)
     */
    public record Line(
            @NonNull UUID orderLineId,
            @NonNull String sku,
            @NonNull String description,
            int quantity,
            @NonNull BigDecimal unitPrice,
            @NonNull BigDecimal discountAmount,
            @NonNull BigDecimal lineSubtotal,
            @NonNull BigDecimal taxAmount,
            @NonNull BigDecimal lineTotal,
            @NonNull String priceSource,
            @Nullable String sourceType,
            @Nullable String sourceId,
            @Nullable String sourceLineId,
            @Nullable Boolean returnable,
            @NonNull List<String> serialNumbers,
            boolean fulfillable) {}

    /**
     * Settlement summary entry.
     *
     * @param methodType CASH / CARD / ON_ACCOUNT / OTHER
     * @param amount settled amount for the method
     */
    public record Tender(
            @NonNull String methodType, @NonNull BigDecimal amount) {}
}
