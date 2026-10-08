package com.positivity.accounting.internal.enums;

/**
 * Why an approved vendor bill's entry carries the date it does (AW42; SPEC-accounting-workspace §4.3 "Dates and
 * refusals"). The bill date wins when it is on or before the approval date and its period is open; otherwise the
 * approval date, in the tenant's accounting calendar.
 */
public enum VendorBillPostingDateRule {
    /** Posted on the bill date: on or before the approval date, in an open period. */
    BILL_DATE,
    /** Posted on the approval date: the bill date's period is closed or hard-locked. */
    APPROVAL_DATE_BILL_PERIOD_NOT_OPEN,
    /** Posted on the approval date: the bill is dated after the approval. */
    APPROVAL_DATE_BILL_DATE_FUTURE
}
