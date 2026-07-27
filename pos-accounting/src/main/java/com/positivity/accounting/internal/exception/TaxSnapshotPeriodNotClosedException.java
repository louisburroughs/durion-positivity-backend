package com.positivity.accounting.internal.exception;

/**
 * Thrown when a Sales-Tax Liability snapshot freeze is requested for an accounting period that is
 * not CLOSED (issue #998: the freeze must align with the accounting close). Maps to 409
 * TAX_SNAPSHOT_PERIOD_NOT_CLOSED.
 */
public class TaxSnapshotPeriodNotClosedException extends RuntimeException {

    private final String periodCode;

    public TaxSnapshotPeriodNotClosedException(String periodCode, String message) {
        super(message);
        this.periodCode = periodCode;
    }

    public String getPeriodCode() {
        return periodCode;
    }
}
