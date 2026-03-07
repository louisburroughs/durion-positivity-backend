package com.positivity.invoice.internal.exception;

/**
 * Thrown when a receipt is not found.
 * Maps to HTTP 404 Not Found (ADR-0017).
 *
 * Story #7.
 */
public class ReceiptNotFoundException extends RuntimeException {

    public ReceiptNotFoundException(String message) {
        super(message);
    }
}
