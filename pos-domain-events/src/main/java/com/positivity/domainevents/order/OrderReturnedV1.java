package com.positivity.domainevents.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a return against a completed sales order finished its orchestration (order parity plan
 * story D2 schema, emitted by story F2; spec R5.3).
 *
 * <p>Published by pos-order on {@code order.events.v1} with
 * {@code eventType = "order.order.returned"} when the return saga completes (refund issued and,
 * for RESTOCK lines, stock returned). Line quantities are positive; the qty-cap invariant
 * (Σ returned ≤ sold per original line) is enforced by the producer.
 *
 * @param returnOrderId return aggregate identifier (also the envelope aggregateId)
 * @param originalOrderId the completed order being returned against
 * @param orderNumber original order's human-facing number
 * @param locationId shop location
 * @param customerId customer party id (ID only)
 * @param refundMethod ORIGINAL_TENDER / STORE_CREDIT / ON_ACCOUNT_CREDIT
 * @param totalRefund refunded amount
 * @param lines returned lines
 * @param returnedAt when the return saga completed
 */
public record OrderReturnedV1(
        @NonNull UUID returnOrderId,
        @NonNull UUID originalOrderId,
        @Nullable String orderNumber,
        @Nullable UUID locationId,
        @Nullable UUID customerId,
        @NonNull String refundMethod,
        @NonNull BigDecimal totalRefund,
        @NonNull List<Line> lines,
        @NonNull Instant returnedAt) {

    public static final String EVENT_TYPE = "order.order.returned";
    public static final int SCHEMA_VERSION = 1;

    /**
     * One returned line.
     *
     * @param originalOrderLineId the sold line being returned against
     * @param sku item SKU
     * @param quantity returned units (positive)
     * @param condition RESTOCK / SCRAP / WARRANTY
     * @param amount refunded amount for the line
     * @param serialNumbers returned serials, when the sale captured them
     */
    public record Line(
            @NonNull UUID originalOrderLineId,
            @NonNull String sku,
            int quantity,
            @NonNull String condition,
            @NonNull BigDecimal amount,
            @NonNull List<String> serialNumbers) {}
}
