package com.positivity.accounting.internal.enums;

import org.jspecify.annotations.NonNull;

/**
 * The kinds of work item (CAP:550 S19, #2515; SPEC-accounting-workspace §7.1 "Work items"): each is built on read from
 * its source and has no state of its own; it exists while the source is in the listed state.
 */
public enum WorkItemType {
    /** A bill in match exception with no candidate to pick: correct, accept or void it. */
    BILL_MATCH_EXCEPTION(WorkItemArea.BILLS),
    /** A bill whose ambiguous match left candidates nobody has picked. */
    BILL_PICK_MATCH(WorkItemArea.BILLS),
    /** A bill waiting for its delivery or its invoice. */
    BILL_WAITING_ON_DELIVERY(WorkItemArea.BILLS),
    /** A bill sent for approval. */
    BILL_AWAITING_APPROVAL(WorkItemArea.BILLS),
    /** An approved bill, open, due within seven days (overdue ones included). */
    BILLS_DUE_SOON(WorkItemArea.BILLS),
    /** A customer payment with a suggested match. */
    PAYMENT_TO_MATCH(WorkItemArea.PAYMENTS),
    /** Defined, produced only once unidentified payments exist (OI-8). */
    PAYMENT_NO_CUSTOMER(WorkItemArea.PAYMENTS),
    /** An open walk-in invoice whose business day has ended. */
    UNPAID_WALK_IN_SALE(WorkItemArea.PAYMENTS),
    /** A vendor payment that did not reach the ledger. */
    AP_PAYMENT_NOT_POSTED(WorkItemArea.PAYMENTS),
    /** The balanced undeposited sessions of one location. */
    CASH_TO_DEPOSIT(WorkItemArea.CASH),
    /** An undeposited session, past the aged threshold, whose drops do not balance. */
    UNDEPOSITED_SESSION_UNBALANCED(WorkItemArea.CASH),
    /** An unmatched line of a reconciliation in progress. */
    BANK_LINE_UNMATCHED(WorkItemArea.BANK),
    /** A submitted reconciliation waiting for its approver. */
    RECONCILIATION_AWAITING_APPROVAL(WorkItemArea.BANK);

    private final WorkItemArea area;

    WorkItemType(WorkItemArea area) {
        this.area = area;
    }

    /** The area the item is listed and counted under. */
    public @NonNull WorkItemArea area() {
        return area;
    }
}
