package com.positivity.accounting.internal.service;

import java.math.BigDecimal;

/**
 * Shared formatting helpers for the deterministic report CSV renderers
 * (issues #999, #1011-#1015, #1018).
 *
 * <p>Figures are emitted with {@link BigDecimal#toPlainString()} so rendered CSV
 * matches the JSON report to the cent; fields containing commas, quotes, CR, or
 * LF are quoted and quote-escaped per RFC 4180.
 *
 * <p>Text fields are additionally neutralized against spreadsheet formula
 * injection (CWE-1236): a cell whose first character (ignoring a leading
 * space-run, which spreadsheets trim on import) is {@code =}, {@code +},
 * {@code -}, {@code @}, tab, or CR is prefixed with a single quote {@code '}
 * per OWASP CSV Injection guidance, so Excel/LibreOffice treat it as text
 * instead of evaluating it. The prefix is part of the rendered bytes and is
 * itself deterministic, so the byte-identical rendering guarantee still holds.
 * Numeric fields go through {@link #amount(BigDecimal)}, which legitimately
 * emits a leading {@code -} for negatives and is deliberately left untouched —
 * never route figures through {@link #escapeText(String)}.
 */
final class CsvFormat {

    private CsvFormat() {}

    /**
     * Format a figure with {@link BigDecimal#toPlainString()} (empty string for
     * null). Negative values keep their plain leading {@code -}; no formula
     * neutralization is applied.
     */
    static String amount(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    /**
     * Escape a free-text cell: neutralize a formula-leading first character with
     * a {@code '} prefix, then apply RFC 4180 quoting.
     */
    static String escapeText(String value) {
        if (value == null) {
            return "";
        }
        if (startsWithFormulaCharacter(value)) {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static boolean startsWithFormulaCharacter(String value) {
        // Skip a leading space-run: spreadsheets trim it on import, so " =2+5"
        // would still evaluate as a formula.
        int i = 0;
        while (i < value.length() && value.charAt(i) == ' ') {
            i++;
        }
        if (i == value.length()) {
            return false;
        }
        char first = value.charAt(i);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }
}
