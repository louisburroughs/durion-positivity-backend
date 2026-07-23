package com.positivity.order.service.model;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * Result of cart creation (plan story I1): {@code replay} is true when an {@code Idempotency-Key}
 * matched an existing cart and the original was returned instead of creating a new one.
 */
public record CreateCartResult(@NonNull SalesOrderSummary summary, boolean replay) {

    public CreateCartResult {
        Objects.requireNonNull(summary, "summary must not be null");
    }
}
