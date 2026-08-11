package com.positivity.price.internal.exception;

import java.time.Instant;
import java.util.UUID;

/**
 * Thrown when a base-price write would overlap or backdate an existing effective window
 * for the same (productId, currency).
 *
 * Issue: #1233
 */
public class BasePriceWindowConflictException extends RuntimeException {

    public BasePriceWindowConflictException(UUID productId, String currency, Instant effectiveFrom) {
        super("Base price window conflict for product " + productId + " (" + currency + "): effectiveFrom "
                + effectiveFrom + " does not start after the latest existing window");
    }
}
