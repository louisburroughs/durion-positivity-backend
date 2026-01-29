package com.positivity.order.internal.exception;

/**
 * Exception thrown when a price override is not found.
 */
public class PriceOverrideNotFoundException extends RuntimeException {
    
    public PriceOverrideNotFoundException(String message) {
        super(message);
    }
    
    public PriceOverrideNotFoundException(Long overrideId) {
        super("Price override not found with ID: " + overrideId);
    }
}
