package com.positivity.accounting.internal.exception;

/**
 * Thrown when a bank reconciliation is started for a GL account whose {@code
 * reconcilable} flag (Story H1) is false (Story F2, issue #965). Maps to 422
 * ACCOUNT_NOT_RECONCILABLE.
 */
public class AccountNotReconcilableException extends RuntimeException {

    public AccountNotReconcilableException(String message) {
        super(message);
    }
}
