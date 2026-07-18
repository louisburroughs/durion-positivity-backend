package com.positivity.accounting.internal.exception;

import java.time.LocalDate;

/**
 * Thrown when a hard-lock date update would move the date backward in time
 * (story B2, issue #944). The hard lock is monotonic-forward-only — that is
 * what makes it irreversible. Maps to 422 with code
 * HARD_LOCK_DATE_REGRESSION.
 */
public class HardLockDateRegressionException extends RuntimeException {

    private final LocalDate currentHardLockDate;
    private final LocalDate requestedHardLockDate;

    public HardLockDateRegressionException(LocalDate currentHardLockDate, LocalDate requestedHardLockDate) {
        super("Hard-lock date can only move forward: requested " + requestedHardLockDate
                + " is before the current hard-lock date " + currentHardLockDate);
        this.currentHardLockDate = currentHardLockDate;
        this.requestedHardLockDate = requestedHardLockDate;
    }

    public LocalDate getCurrentHardLockDate() {
        return currentHardLockDate;
    }

    public LocalDate getRequestedHardLockDate() {
        return requestedHardLockDate;
    }
}
