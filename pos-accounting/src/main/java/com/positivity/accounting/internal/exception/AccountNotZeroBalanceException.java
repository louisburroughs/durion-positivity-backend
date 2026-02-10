package com.positivity.accounting.internal.exception;

/**
 * Exception thrown when attempting to deactivate a GL account that has a
 * non-zero balance.
 * 
 * Should map to HTTP 400 Bad Request.
 */
public class AccountNotZeroBalanceException extends RuntimeException {

    public AccountNotZeroBalanceException(String message) {
        super(message);
    }

    public AccountNotZeroBalanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
