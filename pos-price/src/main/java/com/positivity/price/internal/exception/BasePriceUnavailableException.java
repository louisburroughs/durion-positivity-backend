package com.positivity.price.internal.exception;

import java.time.Instant;
import java.util.UUID;

/**
 * Thrown when no base-price effective window covers the pricing instant for a product.
 *
 * Issue: #1233
 */
public class BasePriceUnavailableException extends RuntimeException {

    public BasePriceUnavailableException(UUID productId, String currency, Instant at) {
        super("No base price effective for product " + productId + " (" + currency + ") at " + at);
    }
}
