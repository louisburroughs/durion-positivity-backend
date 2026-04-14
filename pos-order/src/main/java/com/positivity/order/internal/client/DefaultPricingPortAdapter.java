package com.positivity.order.internal.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Default (stub) implementation of PricingPort.
 * Returns a fixed price of $10.00 for all SKUs.
 * Replace with pos-price REST adapter in a future story.
 */
@Component
public class DefaultPricingPortAdapter implements PricingPort {

    @Override
    public @NonNull PricingResult resolvePrice(@NonNull String itemSku) {
        return new PricingResult(BigDecimal.TEN.setScale(4, RoundingMode.HALF_UP), false, true);
    }
}
