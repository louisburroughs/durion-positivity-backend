package com.positivity.accounting.internal.exception;

/**
 * Exception thrown when attempting to create a GL account with a duplicate
 * account code.
 * 
 * Should map to HTTP 409 Conflict.
 */
public class DuplicateAccountCodeException extends RuntimeException {

    public DuplicateAccountCodeException(String message) {
        super(message);
    }

    public DuplicateAccountCodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
