package com.positivity.order.service.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public record SalesOrderLineSummary(
        @NonNull String orderLineId,
        @NonNull String itemSku,
        @NonNull String itemDescription,
        int quantity,
        @NonNull BigDecimal unitPrice,
        BigDecimal discountPercent,
        BigDecimal discountAmount,
        BigDecimal lineSubtotal,
        BigDecimal taxAmount,
        BigDecimal lineTotal,
        @NonNull String fulfillmentStatus,
        @NonNull String priceSource,
        String reasonCode,
        String customerNote,
        String internalNote,
        String sourceType,
        String sourceId,
        String sourceLineId,
        List<String> serialNumbers,
        Boolean returnable) {

    public SalesOrderLineSummary {
        Objects.requireNonNull(orderLineId, "orderLineId must not be null");
        Objects.requireNonNull(itemSku, "itemSku must not be null");
        Objects.requireNonNull(itemDescription, "itemDescription must not be null");
        Objects.requireNonNull(unitPrice, "unitPrice must not be null");
        Objects.requireNonNull(fulfillmentStatus, "fulfillmentStatus must not be null");
        Objects.requireNonNull(priceSource, "priceSource must not be null");
        serialNumbers = Objects.requireNonNullElse(serialNumbers, List.of());
    }
}
