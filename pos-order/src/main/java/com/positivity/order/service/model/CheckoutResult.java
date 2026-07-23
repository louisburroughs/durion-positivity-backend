package com.positivity.order.service.model;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * Result of checkout (parity story C1): the frozen, priced order in PENDING_PAYMENT with its
 * invoice reference. {@code replay} is true when the Idempotency-Key matched a previous checkout
 * of the same order.
 */
public record CheckoutResult(@NonNull SalesOrderSummary summary, boolean replay) {

    public CheckoutResult {
        Objects.requireNonNull(summary, "summary must not be null");
    }
}
