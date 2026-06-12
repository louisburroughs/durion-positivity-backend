package com.positivity.inventory.internal.exception;

/**
 * Raised when pos-location cannot be reached or returns a server error while
 * pos-inventory needs authoritative topology data. Rollup reads must never
 * fabricate partial topology (CAP-218 #658).
 */
public class LocationServiceUnavailableException extends RuntimeException {
    public LocationServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
