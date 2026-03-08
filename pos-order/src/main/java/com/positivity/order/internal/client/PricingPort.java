package com.positivity.order.internal.client;

import org.jspecify.annotations.NonNull;

public interface PricingPort {
    @NonNull
    PricingResult resolvePrice(@NonNull String itemSku);
}
