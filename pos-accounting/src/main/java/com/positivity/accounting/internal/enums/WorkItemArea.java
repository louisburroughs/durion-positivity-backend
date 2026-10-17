package com.positivity.accounting.internal.enums;

/**
 * The areas of the to-do list (CAP:550 S19, #2515; SPEC-accounting-workspace §7.1 "Work items", ruling 7 of
 * 2026-10-17): the grouping the home pane filters by and counts.
 */
public enum WorkItemArea {
    /** Vendor bills to check, approve or pay. */
    BILLS,
    /** Customer and vendor payments to match or post, and unpaid walk-in sales. */
    PAYMENTS,
    /** Bank statement lines and reconciliations. */
    BANK,
    /** Drawer cash waiting for the bank. */
    CASH
}
