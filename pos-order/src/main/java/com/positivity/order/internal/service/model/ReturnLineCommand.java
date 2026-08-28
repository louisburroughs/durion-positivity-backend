package com.positivity.order.internal.service.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One requested return line (parity story F1, #1086).
 *
 * @param originalOrderLineId the sold line (sales_order_line id) being returned against
 * @param returnQty units to return (must be positive; capped at the un-refunded remainder)
 * @param condition RESTOCK / SCRAP / WARRANTY
 * @param serialNumbers returned serials, when the sale captured them
 */
public record ReturnLineCommand(
        @NonNull UUID originalOrderLineId,
        int returnQty,
        @NonNull String condition,
        @Nullable List<String> serialNumbers) {

    public ReturnLineCommand {
        Objects.requireNonNull(originalOrderLineId, "originalOrderLineId must not be null");
        Objects.requireNonNull(condition, "condition must not be null");
        serialNumbers = serialNumbers == null ? List.of() : List.copyOf(serialNumbers);
    }
}
