package com.positivity.accounting.internal.exception;

/**
 * Exception thrown when attempting to deactivate a GL account that has a
 * non-zero balance. The account's current state blocks the operation, not
 * the shape of the request.
 *
 * <p>Maps to HTTP 409 (ACCOUNT_NOT_ZERO_BALANCE) per ADR-0017 §2.
 */
public class AccountNotZeroBalanceException extends RuntimeException {

    public AccountNotZeroBalanceException(String message) {
        super(message);
    }

    public AccountNotZeroBalanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
