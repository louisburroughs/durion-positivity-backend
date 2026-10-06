package com.positivity.accounting.internal.exception;

/**
 * The tenant's accounting time zone can no longer change (#2558): a period was closed, or a hard-lock date was set,
 * so a new zone would move the boundary of a month already cut. Maps to 409 {@code ACCOUNTING_TIME_ZONE_LOCKED}.
 */
public class AccountingTimeZoneLockedException extends RuntimeException {

    public AccountingTimeZoneLockedException(String currentZone, String requestedZone) {
        super("The accounting time zone is " + currentZone + " and can no longer change to " + requestedZone
                + ": a period was closed or a hard-lock date was set, and a period already cut keeps its boundary");
    }
}
