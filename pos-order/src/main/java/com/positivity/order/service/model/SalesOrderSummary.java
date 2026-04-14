package com.positivity.order.service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public record SalesOrderSummary(
        @NonNull String orderId,
        String customerId,
        String vehicleId,
        @NonNull String clerkId,
        @NonNull String terminalId,
        @NonNull String status,
        @NonNull BigDecimal subtotal,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy,
        List<SalesOrderLineSummary> lines) {

    public SalesOrderSummary {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(clerkId, "clerkId must not be null");
        Objects.requireNonNull(terminalId, "terminalId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(subtotal, "subtotal must not be null");
        lines = Objects.requireNonNullElse(lines, List.of());
    }
}
