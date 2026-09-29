package com.positivity.accounting.internal.exception;

/**
 * A bank reconciliation close exception asked for by a caller without both {@code accounting:period:close} and
 * {@code accounting:period:override} (SPEC-manual-bank-reconciliation §5.2, I5, D4; story S6, #2305): 403 {@code
 * PERIOD_CLOSE_EXCEPTION_NOT_PERMITTED}; the period stays OPEN and no exception audit row is written.
 */
public class PeriodCloseExceptionNotPermittedException extends RuntimeException {

    public PeriodCloseExceptionNotPermittedException(String message) {
        super(message);
    }
}
