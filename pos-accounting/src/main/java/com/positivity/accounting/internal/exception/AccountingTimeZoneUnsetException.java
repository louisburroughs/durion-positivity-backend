package com.positivity.accounting.internal.exception;

/**
 * The bound tenant has no {@code ACCOUNTING_TIME_ZONE} setting, so no instant can be given a posting date (#2558).
 *
 * <p>There is no default zone: posting fails closed until an administrator sets the tenant's accounting-calendar
 * zone. A consumed fact is held {@code SUSPENDED / ACCOUNTING_TIME_ZONE_UNSET}; a request answers 422 with that code.
 */
public class AccountingTimeZoneUnsetException extends RuntimeException {

    public AccountingTimeZoneUnsetException() {
        super("The tenant's accounting time zone (ACCOUNTING_TIME_ZONE) is not set; set it with"
                + " PUT /v1/accounting/configuration/time-zone before posting");
    }
}
