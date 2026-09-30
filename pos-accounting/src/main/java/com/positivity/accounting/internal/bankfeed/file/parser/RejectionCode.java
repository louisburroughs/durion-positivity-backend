package com.positivity.accounting.internal.bankfeed.file.parser;

/**
 * Why one row of a statement file is {@code REJECTED} (SPEC-manual-bank-reconciliation §4.4, G8;
 * story S3, #2302): a bad row is rejected with a code, never the whole file.
 */
public enum RejectionCode {
    /** The date cell is not a date under the import's date format. */
    DATE_UNPARSEABLE,
    /** The amount cell is not a number, or carries more than four decimal places. */
    AMOUNT_UNPARSEABLE,
    /** The amount is zero; a bank line always moves money. */
    AMOUNT_ZERO,
    /** Both the debit and the credit column carry an amount ({@code DEBIT_CREDIT_COLUMNS}). */
    AMOUNT_AND_DEBIT_CREDIT_BOTH,
    /** A mapped column is absent from the row, or a required value (date, amount, description) is blank. */
    REQUIRED_COLUMN_MISSING,
    /** A corrected date lies outside the statement window (an uncorrected one is {@code OUT_OF_WINDOW}). */
    DATE_OUTSIDE_STATEMENT,
    /**
     * The amount has more decimals than the import currency's minor unit; trailing zeros do not count (ADR-0067
     * PC-6, #2336). Staged at upload rather than refused at commit, so the preparer can correct the row.
     */
    AMOUNT_PRECISION_EXCEEDS_CURRENCY
}
