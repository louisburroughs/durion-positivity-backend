package com.positivity.order.internal.exception;

/**
 * Exception thrown when a price override violates business rules.
 */
public class InvalidPriceOverrideException extends RuntimeException {

    public InvalidPriceOverrideException(String message) {
        super(message);
    }

    public InvalidPriceOverrideException(String message, Throwable cause) {
        super(message, cause);
    }
}
