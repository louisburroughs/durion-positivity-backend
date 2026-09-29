package com.positivity.accounting.internal.bankrec.enums;

/**
 * Which accounts close readiness evaluates (SPEC-manual-bank-reconciliation §5.2, D5; story S6, #2305). Stored
 * in {@code accounting_configuration} under {@code BANK_REC_CLOSE_SCOPE}; absent means {@link #BANK_CASH_SUBTYPE}.
 */
public enum BankRecCloseScope {
    /** Active {@code reconcilable} accounts with {@code accountSubtype = BANK_CASH}. */
    BANK_CASH_SUBTYPE,
    /** Every active {@code reconcilable} account, whatever its subtype. */
    ALL_RECONCILABLE
}
