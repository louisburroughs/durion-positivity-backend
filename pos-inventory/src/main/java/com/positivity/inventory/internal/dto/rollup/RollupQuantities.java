package com.positivity.inventory.internal.dto.rollup;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Quantity triple used at every rollup node. {@code available} is
 * {@code onHand - allocated} and is intentionally NOT clamped — negative
 * available is a real over-allocation signal (CAP-218 #658).
 */
public record RollupQuantities(
        @Schema(description = "On-hand quantity", requiredMode = Schema.RequiredMode.REQUIRED)
        long onHand,

        @Schema(description = "Outstanding allocated quantity", requiredMode = Schema.RequiredMode.REQUIRED)
        long allocated,

        @Schema(
                description = "Available to promise: onHand - allocated (may be negative)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        long available) {

    public static final RollupQuantities ZERO = new RollupQuantities(0, 0, 0);

    public static RollupQuantities of(long onHand, long allocated) {
        return new RollupQuantities(onHand, allocated, onHand - allocated);
    }

    public RollupQuantities plus(RollupQuantities other) {
        return of(onHand + other.onHand, allocated + other.allocated);
    }

    public boolean isEmpty() {
        return onHand == 0 && allocated == 0;
    }
}
