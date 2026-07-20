package com.positivity.accounting.internal.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Parses a bank statement CSV (Story F2, issue #965) into typed rows. Columns:
 * {@code date, description, amount [signed], reference}. Robust to a header row,
 * surrounding whitespace, simple double-quoted fields, a leading {@code $},
 * thousands separators inside quoted amounts, and negative amounts expressed either
 * with a leading minus or wrapped in parentheses. Input may be raw text lines or
 * base64-encoded text. Malformed rows raise {@link IllegalArgumentException} (mapped
 * to 400) with the offending line number.
 */
final class BankStatementCsvParser {

    private BankStatementCsvParser() {}

    /** One parsed statement row. */
    record ParsedLine(LocalDate date, String description, BigDecimal amount, String reference) {}

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"));

    static List<ParsedLine> parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("CSV content is empty");
        }
        String text = decodeIfBase64(input);
        String[] rawLines = text.split("\\r?\\n");

        List<ParsedLine> result = new ArrayList<>();
        boolean headerChecked = false;
        for (int i = 0; i < rawLines.length; i++) {
            String raw = rawLines[i].trim();
            if (raw.isEmpty()) {
                continue;
            }
            List<String> fields = splitCsv(raw);
            // Skip a single header row only when it clearly IS a header: its first field is not a date
            // AND its amount column is non-numeric. This prevents silently dropping a header-less first
            // data row whose date is malformed — such a row is not a header, so it falls through to
            // parseLine and raises a clear 400 with the line number instead of vanishing.
            if (!headerChecked) {
                headerChecked = true;
                if (isHeaderRow(fields)) {
                    continue;
                }
            }
            result.add(parseLine(fields, i + 1));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("CSV contained no statement lines");
        }
        return result;
    }

    /**
     * A row is treated as a header only when its first field is not a date AND its amount column
     * (3rd field, if present) does not parse as an amount. A header-less data row always has a numeric
     * amount, so it is never mistaken for a header and is instead validated (and rejected on a bad date).
     */
    private static boolean isHeaderRow(List<String> fields) {
        if (fields.isEmpty()) {
            return false;
        }
        if (tryParseDate(fields.get(0).trim()) != null) {
            return false;
        }
        if (fields.size() >= 3 && tryParseAmount(fields.get(2).trim()) != null) {
            // First field is not a date but the amount column IS numeric → a malformed data row, not a
            // header. Let it fall through to parseLine to fail loudly.
            return false;
        }
        return true;
    }

    private static ParsedLine parseLine(List<String> fields, int lineNumber) {
        if (fields.size() < 3) {
            throw new IllegalArgumentException("CSV line " + lineNumber
                    + " must have at least 3 columns (date, description, amount); found " + fields.size());
        }
        LocalDate date = tryParseDate(fields.get(0).trim());
        if (date == null) {
            throw new IllegalArgumentException("CSV line " + lineNumber + " has an unparseable date: '"
                    + fields.get(0).trim() + "'");
        }
        String description = fields.get(1).trim();
        BigDecimal amount = parseAmount(fields.get(2).trim(), lineNumber);
        String reference = fields.size() >= 4 ? fields.get(3).trim() : null;
        if (reference != null && reference.isEmpty()) {
            reference = null;
        }
        return new ParsedLine(date, description, amount, reference);
    }

    private static BigDecimal parseAmount(String raw, int lineNumber) {
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("CSV line " + lineNumber + " has an empty amount");
        }
        BigDecimal value = tryParseAmount(raw);
        if (value == null) {
            throw new IllegalArgumentException("CSV line " + lineNumber + " has an unparseable amount: '" + raw + "'");
        }
        return value;
    }

    /** Parse a (possibly $-prefixed, thousands-separated, parenthesized-negative) amount, or null. */
    private static BigDecimal tryParseAmount(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String cleaned = raw.replace("$", "").replace(",", "").replace(" ", "");
        boolean parenNegative = cleaned.startsWith("(") && cleaned.endsWith(")");
        if (parenNegative) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        try {
            BigDecimal value = new BigDecimal(cleaned);
            return parenNegative ? value.negate() : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate tryParseDate(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, fmt);
            } catch (Exception ignored) {
                // try next format
            }
        }
        return null;
    }

    /**
     * Base64 heuristic: a raw CSV always contains a comma (the field delimiter), which is not in the
     * base64 alphabet. So a comma-free input that decodes to text containing a comma is treated as
     * base64; otherwise the input is used as-is.
     */
    private static String decodeIfBase64(String input) {
        String trimmed = input.trim();
        if (trimmed.contains(",")) {
            return input;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed.replaceAll("\\s", ""));
            String decodedText = new String(decoded, StandardCharsets.UTF_8);
            if (decodedText.contains(",")) {
                return decodedText;
            }
        } catch (IllegalArgumentException ignored) {
            // not base64; fall through
        }
        return input;
    }

    /** Minimal CSV field splitter supporting double-quoted fields with embedded commas and "" escapes. */
    private static List<String> splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
