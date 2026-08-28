package com.positivity.order.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read model of a return order and its lines (parity stories F1/F2, #1086/#1087).
 *
 * @param returnOrderId return identifier
 * @param originalOrderId the returned-against order
 * @param originalOrderNumber original order's human-facing number
 * @param locationId shop location
 * @param customerId customer party id
 * @param refundMethod ORIGINAL_TENDER / STORE_CREDIT / ON_ACCOUNT_CREDIT
 * @param status saga status
 * @param reasonCode return reason
 * @param totalRefund total refunded amount
 * @param failureReason saga failure detail, when parked
 * @param lines returned lines
 * @param returnedAt when the return saga completed (null until then)
 * @param createdAt creation time
 */
public record ReturnOrderSummary(
        @NonNull UUID returnOrderId,
        @NonNull UUID originalOrderId,
        @Nullable String originalOrderNumber,
        @Nullable UUID locationId,
        @Nullable UUID customerId,
        @NonNull String refundMethod,
        @NonNull String status,
        @Nullable String reasonCode,
        @NonNull BigDecimal totalRefund,
        @Nullable String failureReason,
        @NonNull List<Line> lines,
        @Nullable Instant returnedAt,
        @NonNull Instant createdAt) {

    /**
     * One returned line.
     *
     * @param returnLineId return line id
     * @param originalOrderLineId the sold line returned against
     * @param itemSku item SKU
     * @param returnQty returned units
     * @param condition RESTOCK / SCRAP / WARRANTY
     * @param lineRefund refunded amount for the line
     * @param serialNumbers returned serials
     */
    public record Line(
            @NonNull UUID returnLineId,
            @NonNull UUID originalOrderLineId,
            @NonNull String itemSku,
            int returnQty,
            @NonNull String condition,
            @NonNull BigDecimal lineRefund,
            @NonNull List<String> serialNumbers) {}
}
