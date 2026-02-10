package com.positivity.accounting.internal.exception;

/**
 * Exception thrown when a GL account is not found by ID or account code.
 * 
 * Should map to HTTP 404 Not Found.
 */
public class GLAccountNotFoundException extends RuntimeException {

    public GLAccountNotFoundException(String message) {
        super(message);
    }

    public GLAccountNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
