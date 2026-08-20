package com.positivity.inventory.internal.exception;

import java.math.BigDecimal;

/**
 * Exception thrown when an invalid quantity is provided for a count.
 *
 * <p>Per issue #27, quantities must be non-negative. Since ADR-0055 stage 4 (#1416) a count
 * quantity may carry decimal places — divisibility is now a per-product declaration
 * ({@code precision_scale}, enforced separately via {@code QuantityScaleGuard}) rather than a
 * blanket whole-number rule.
 */
public class InvalidCountQuantityException extends RuntimeException {

    public InvalidCountQuantityException(String message) {
        super(message);
    }

    public InvalidCountQuantityException(BigDecimal quantity) {
        super(String.format("Invalid count quantity: %s. Quantity must be zero or positive.", quantity));
    }
}
