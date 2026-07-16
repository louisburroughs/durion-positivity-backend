package com.positivity.warranty.internal.exception;

/**
 * Raised when an outbound call to a sibling service fails in a way pos-warranty
 * must not silently absorb (e.g. settlement writes to pos-invoice).
 *
 * <p>Read-path clients degrade gracefully (empty {@code Optional}/list) instead of
 * throwing; write-path clients wrap transport/HTTP failures in this exception so
 * settlements fail loudly and the caller can roll back.
 */
public class WarrantyIntegrationException extends RuntimeException {

    public WarrantyIntegrationException(String message) {
        super(message);
    }

    public WarrantyIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
