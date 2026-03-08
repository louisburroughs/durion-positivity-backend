package com.positivity.order.internal.client;

import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;

public record PricingResult(
        @NonNull BigDecimal price,
        boolean stale,
        boolean found) {
}
