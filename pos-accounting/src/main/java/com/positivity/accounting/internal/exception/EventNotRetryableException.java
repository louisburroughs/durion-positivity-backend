package com.positivity.accounting.internal.exception;

/**
 * Thrown when a retry is requested for an accounting event that is not {@code FAILED} (#2411).
 * Maps to HTTP 409 {@code EVENT_NOT_RETRYABLE}; the event is left unchanged.
 */
public class EventNotRetryableException extends RuntimeException {

    public EventNotRetryableException(String message) {
        super(message);
    }
}
