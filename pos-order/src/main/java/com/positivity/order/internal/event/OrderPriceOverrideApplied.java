package com.positivity.order.internal.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Domain event published when a price override is auto-approved and applied
 * immediately.
 */
public record OrderPriceOverrideApplied(
        UUID overrideId,
        UUID orderId,
        UUID orderLineId,
        UUID productId,
        BigDecimal originalPrice,
        BigDecimal overridePrice,
        String requestedByUsername) {
}
