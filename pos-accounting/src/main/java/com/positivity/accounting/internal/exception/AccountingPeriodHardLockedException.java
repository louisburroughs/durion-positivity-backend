package com.positivity.accounting.internal.exception;

import java.time.LocalDate;

/**
 * Thrown when an operation targets a transaction date strictly before the
 * org-level hard-lock date (story B2, issue #944). Unlike
 * {@link AccountingPeriodClosedException}, there is no override path — the
 * hard lock is unconditional and irreversible. Maps to 422 with code
 * PERIOD_HARD_LOCKED.
 */
public class AccountingPeriodHardLockedException extends RuntimeException {

    private final LocalDate hardLockDate;

    public AccountingPeriodHardLockedException(LocalDate hardLockDate, String message) {
        super(message);
        this.hardLockDate = hardLockDate;
    }

    public LocalDate getHardLockDate() {
        return hardLockDate;
    }
}
