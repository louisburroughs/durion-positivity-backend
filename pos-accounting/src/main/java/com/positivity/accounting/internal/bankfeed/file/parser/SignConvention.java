package com.positivity.accounting.internal.bankfeed.file.parser;

/**
 * How a statement file states the direction of money (SPEC-manual-bank-reconciliation §3.3, §4.4;
 * story S3, #2302). Every convention is normalized at parse time to the core's: <b>positive = money
 * into the account</b> (§2.2 "the adapter normalizes formats").
 */
public enum SignConvention {
    /** One signed amount column, positive = cash in (the default and the F2 convention). */
    SIGNED_AMOUNT,
    /** One signed amount column, positive = cash out (banks that show withdrawals positive). */
    SIGNED_AMOUNT_INVERTED,
    /** A debit column (cash out) and a credit column (cash in), each an unsigned amount. */
    DEBIT_CREDIT_COLUMNS
}
