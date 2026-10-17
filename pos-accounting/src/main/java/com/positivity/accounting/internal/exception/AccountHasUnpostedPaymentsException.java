package com.positivity.accounting.internal.exception;

/**
 * A bank account cannot be deactivated while an AP payment that names it has not reached the ledger (#2645): the
 * payment's entry would later put a balance on an inactive account that nobody reconciles. 409 {@code
 * ACCOUNT_HAS_UNPOSTED_PAYMENTS}.
 */
public class AccountHasUnpostedPaymentsException extends RuntimeException {
    public AccountHasUnpostedPaymentsException(String message) {
        super(message);
    }
}
