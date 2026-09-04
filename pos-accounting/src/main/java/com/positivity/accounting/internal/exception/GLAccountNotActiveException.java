package com.positivity.accounting.internal.exception;

/**
 * Thrown when an operation requires an active GL account but the account is
 * not yet active, has already been deactivated as of the transaction date,
 * or has never been activated. The account exists and the request is
 * otherwise well-formed; this is a domain-policy violation on valid input.
 * Maps to HTTP 422 (GL_ACCOUNT_NOT_ACTIVE) per ADR-0017 §2.
 */
public class GLAccountNotActiveException extends RuntimeException {

    public GLAccountNotActiveException(String message) {
        super(message);
    }
}
