package com.positivity.accounting.internal.exception;

/**
 * Exception thrown when an accounting event is not found by ID.
 * 
 * Should map to HTTP 404 Not Found.
 */
public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(String message) {
        super(message);
    }

    public EventNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
