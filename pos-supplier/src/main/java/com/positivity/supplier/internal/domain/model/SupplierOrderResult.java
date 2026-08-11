package com.positivity.supplier.internal.domain.model;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Vendor outcome of an order-create exchange, mapping onto the
 * {@code supplier.order.confirmed} / {@code supplier.order.rejected} result events
 * (ADR-0049 §3). Ambiguous outcomes (timeout after send) never produce this record — they are
 * the orchestrator's reconciliation concern (ADR-0052 §3), not a codec result.
 *
 * @param status vendor decision
 * @param supplierOrderNumber vendor-native order reference, carried as an attribute
 *     (ADR-0049 §2); present on confirmation when the vendor returns one
 * @param vendorReason vendor-supplied rejection reason, verbatim
 */
public record SupplierOrderResult(
        @NonNull Status status, @Nullable String supplierOrderNumber, @Nullable String vendorReason) {

    public enum Status {
        CONFIRMED,
        REJECTED
    }

    public SupplierOrderResult {
        Objects.requireNonNull(status, "status must not be null");
    }
}
