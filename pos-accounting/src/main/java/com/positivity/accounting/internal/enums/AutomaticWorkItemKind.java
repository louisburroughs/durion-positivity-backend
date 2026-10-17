package com.positivity.accounting.internal.enums;

/** The kinds of "done automatically" item (CAP:550 S19, #2515; SPEC-accounting-workspace §7.1 "Done automatically"). */
public enum AutomaticWorkItemKind {
    /** A customer payment applied to its invoice with nobody's hand; may carry {@code UNDO}. */
    PAYMENT_APPLIED_AUTOMATICALLY,
    /** A processor settlement line matched to its receipt automatically; no undo. */
    SETTLEMENT_LINE_MATCHED,
    /** A vendor bill approved by the system approver; no undo, approved bills are locked. */
    BILL_APPROVED_BY_SYSTEM
}
