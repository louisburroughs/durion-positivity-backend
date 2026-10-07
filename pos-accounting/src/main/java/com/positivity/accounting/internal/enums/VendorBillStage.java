package com.positivity.accounting.internal.enums;

/**
 * The four stages of the Bills to pay review (#2509; SPEC-accounting-workspace §5.2), derived from a bill's status
 * and open amount, never stored.
 */
public enum VendorBillStage {
    /** {@code PENDING_RECEIPT_MATCH}, {@code MATCH_EXCEPTION} and {@code CURRENCY_HOLD}: oldest first. */
    CHECK,
    /** {@code AWAITING_APPROVAL}: oldest submission first. */
    APPROVE,
    /** {@code APPROVED} with an open amount above 0: by due date, bills without one last. */
    PAY,
    /** {@code APPROVED}, open amount 0, last payment dated in the current month: newest paid first. */
    DONE
}
