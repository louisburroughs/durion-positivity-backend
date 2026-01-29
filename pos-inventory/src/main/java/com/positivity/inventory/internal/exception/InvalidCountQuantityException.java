package com.positivity.inventory.internal.exception;

/**
 * Exception thrown when an invalid quantity is provided for a count.
 * 
 * <p>Per issue #27, quantities must be non-negative integers.
 */
public class InvalidCountQuantityException extends RuntimeException {
    
    public InvalidCountQuantityException(String message) {
        super(message);
    }
    
    public InvalidCountQuantityException(Integer quantity) {
        super(String.format(
            "Invalid count quantity: %s. Quantity must be zero or a positive whole number.",
            quantity
        ));
    }
}
