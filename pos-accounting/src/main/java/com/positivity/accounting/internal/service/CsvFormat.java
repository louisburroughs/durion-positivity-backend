package com.positivity.accounting.internal.service;

import java.math.BigDecimal;

/**
 * Shared formatting helpers for the deterministic report CSV renderers
 * (issues #999, #1011-#1015).
 *
 * <p>Figures are emitted with {@link BigDecimal#toPlainString()} so rendered CSV
 * matches the JSON report to the cent; fields containing commas, quotes, CR, or
 * LF are quoted and quote-escaped per RFC 4180.
 */
final class CsvFormat {

    private CsvFormat() {}

    static String amount(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
