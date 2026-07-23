package com.positivity.order.internal.client;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of a price resolution (parity story B1).
 *
 * @param status PRICED on success; UNKNOWN_SKU when the product replica has no active row for the
 *     SKU; UNAVAILABLE when the replica is cold or pos-price is unreachable (the clerk falls back
 *     to a permissioned manual price)
 * @param unitPrice quoted unit price (PRICED only)
 * @param productId catalog product id from the replica (PRICED only)
 * @param description denormalized product name for the receipt (PRICED only)
 * @param trackingLevel catalog tracking level name (NONE/LOT/SERIAL; consumed by story H3)
 * @param breakdownJson pos-price rule breakdown serialized for line-level audit
 */
public record PricingQuote(
        Status status,
        @Nullable BigDecimal unitPrice,
        @Nullable UUID productId,
        @Nullable String description,
        @Nullable String trackingLevel,
        @Nullable String breakdownJson) {

    public enum Status {
        PRICED,
        UNKNOWN_SKU,
        UNAVAILABLE
    }

    public static PricingQuote unknownSku() {
        return new PricingQuote(Status.UNKNOWN_SKU, null, null, null, null, null);
    }

    public static PricingQuote unavailable() {
        return new PricingQuote(Status.UNAVAILABLE, null, null, null, null, null);
    }
}
