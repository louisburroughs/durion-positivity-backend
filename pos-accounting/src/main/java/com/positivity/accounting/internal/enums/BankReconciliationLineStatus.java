package com.positivity.accounting.internal.enums;

/**
 * Match status of an imported bank statement line (Story F2, issue #965).
 * A line starts {@link #UNMATCHED} at import and becomes {@link #MATCHED} when it
 * is linked to one or more posted GL journal-entry lines; unmatching returns it to
 * {@link #UNMATCHED}.
 */
public enum BankReconciliationLineStatus {
    UNMATCHED,
    MATCHED
}
