package com.positivity.accounting.internal.exception;

/**
 * Thrown when an accounting period cannot be resolved for a lifecycle
 * operation: the period row does not exist and cannot be auto-provisioned
 * (its month has not started yet). Maps to 404 PERIOD_NOT_FOUND.
 */
public class AccountingPeriodNotFoundException extends RuntimeException {

    private final String periodCode;

    public AccountingPeriodNotFoundException(String periodCode, String message) {
        super(message);
        this.periodCode = periodCode;
    }

    public String getPeriodCode() {
        return periodCode;
    }
}
