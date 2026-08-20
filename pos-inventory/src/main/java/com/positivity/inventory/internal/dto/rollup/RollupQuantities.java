package com.positivity.inventory.internal.dto.rollup;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * Quantity triple used at every rollup node. {@code available} is
 * {@code onHand - allocated} and is intentionally NOT clamped — negative
 * available is a real over-allocation signal (CAP-218 #658).
 */
@Schema(description = "On-hand, allocated and available quantity triple used at every rollup node")
public record RollupQuantities(
        @Schema(description = "On-hand quantity", example = "120", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal onHand,

        @Schema(
                description = "Outstanding allocated quantity",
                example = "30",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal allocated,

        @Schema(
                description = "Available to promise: onHand - allocated (may be negative)",
                example = "90",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal available) {

    public static final RollupQuantities ZERO = new RollupQuantities(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    public static RollupQuantities of(BigDecimal onHand, BigDecimal allocated) {
        return new RollupQuantities(onHand, allocated, onHand.subtract(allocated));
    }

    public RollupQuantities plus(RollupQuantities other) {
        return of(onHand.add(other.onHand), allocated.add(other.allocated));
    }

    /** {@code signum}, not {@code equals}: {@code 0.0000} from numeric(19,4) is still empty. */
    public boolean isEmpty() {
        return onHand.signum() == 0 && allocated.signum() == 0;
    }
}
