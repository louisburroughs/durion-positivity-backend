package com.positivity.accounting.internal.exception;

/**
 * Exception thrown when attempting to archive a GL account that is not in
 * INACTIVE status. A lifecycle-precondition violation (archiving requires
 * the account to already be INACTIVE), not a malformed request.
 *
 * <p>Maps to HTTP 409 (ACCOUNT_NOT_INACTIVE) per ADR-0017 §2.
 */
public class AccountNotInactiveException extends RuntimeException {

    public AccountNotInactiveException(String message) {
        super(message);
    }

    public AccountNotInactiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
