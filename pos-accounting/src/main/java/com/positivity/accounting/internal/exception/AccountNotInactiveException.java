package com.positivity.accounting.internal.exception;

/**
 * Exception thrown when attempting to archive a GL account that is not in
 * INACTIVE status.
 *
 * Should map to HTTP 400 Bad Request.
 */
public class AccountNotInactiveException extends RuntimeException {

    public AccountNotInactiveException(String message) {
        super(message);
    }

    public AccountNotInactiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
