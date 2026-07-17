package com.positivity.accounting.internal.exception;

import com.positivity.accounting.internal.enums.AccountingPeriodStatus;

/**
 * Thrown when a period lifecycle transition is requested against a period
 * already in the target state (close an already-CLOSED period, reopen an
 * already-OPEN period). Maps to 409 with code PERIOD_ALREADY_CLOSED /
 * PERIOD_ALREADY_OPEN depending on the current status.
 */
public class AccountingPeriodStateException extends RuntimeException {

    private final String periodCode;
    private final AccountingPeriodStatus currentStatus;

    public AccountingPeriodStateException(String periodCode, AccountingPeriodStatus currentStatus, String message) {
        super(message);
        this.periodCode = periodCode;
        this.currentStatus = currentStatus;
    }

    public String getPeriodCode() {
        return periodCode;
    }

    public AccountingPeriodStatus getCurrentStatus() {
        return currentStatus;
    }
}
