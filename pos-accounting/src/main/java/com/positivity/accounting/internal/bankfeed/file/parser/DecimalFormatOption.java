package com.positivity.accounting.internal.bankfeed.file.parser;

/**
 * The decimal separator of a statement file's amounts (SPEC §3.3 {@code decimalFormat}; story S3,
 * #2302). The other of {@code .} and {@code ,} is the thousands separator and is dropped.
 */
public enum DecimalFormatOption {
    /** {@code 1,234.56} — the default. */
    DECIMAL_POINT,
    /** {@code 1.234,56}. */
    DECIMAL_COMMA
}
