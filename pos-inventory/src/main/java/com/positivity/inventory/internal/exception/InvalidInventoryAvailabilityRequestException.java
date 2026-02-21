package com.positivity.inventory.internal.exception;

/**
 * Raised when an inventory availability request is structurally invalid.
 */
public class InvalidInventoryAvailabilityRequestException extends RuntimeException {

    public InvalidInventoryAvailabilityRequestException(String message) {
        super(message);
    }
}
