package com.positivity.accounting.internal.exception;

/**
 * Thrown when an operation targets a transaction date inside a CLOSED
 * accounting period (AD-012). First used by journal-entry reversal date
 * validation (story A3, issue #943); story B2 extends the same code to all
 * posting paths. Maps to 422 with code PERIOD_CLOSED.
 */
public class AccountingPeriodClosedException extends RuntimeException {

    private final String periodCode;

    public AccountingPeriodClosedException(String periodCode, String message) {
        super(message);
        this.periodCode = periodCode;
    }

    public String getPeriodCode() {
        return periodCode;
    }
}
