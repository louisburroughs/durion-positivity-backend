package com.positivity.accounting.internal.exception;

/**
 * A requested accounting time zone is not an IANA region id (#2558): unknown, a fixed offset such as {@code +05:00},
 * {@code UTC+05:00} or {@code Etc/GMT+5}, or a {@code SystemV/*} id. Maps to 400 {@code INVALID_ACCOUNTING_TIME_ZONE}.
 */
public class InvalidAccountingTimeZoneException extends RuntimeException {

    public InvalidAccountingTimeZoneException(String requested, String reason) {
        super("'" + requested + "' is not a valid accounting time zone: " + reason);
    }
}
