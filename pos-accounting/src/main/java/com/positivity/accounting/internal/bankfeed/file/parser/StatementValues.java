package com.positivity.accounting.internal.bankfeed.file.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Cell-level parsing of statement values (SPEC §4.4; story S3, #2302), shared by the file parsers and
 * row corrections. It carries the F2 behaviours: ISO and US dates, a leading currency symbol,
 * thousands separators, and negatives written with a minus sign or in parentheses.
 */
public final class StatementValues {

    /** The F2 date formats, tried in order when the import names none. */
    private static final List<DateTimeFormatter> DEFAULT_DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/yyyy", Locale.ROOT),
            DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT));

    private static final Pattern CURRENCY_AND_BLANKS = Pattern.compile("[\\s$€£¥ ]");

    private StatementValues() {}

    /** The date in {@code raw}, or null when it is blank or not a date under {@code options}. */
    public static @Nullable LocalDate parseDate(@Nullable String raw, @NonNull ParserOptions options) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        List<DateTimeFormatter> formats = options.datePattern() == null
                ? DEFAULT_DATE_FORMATS
                : List.of(DateTimeFormatter.ofPattern(options.datePattern(), Locale.ROOT));
        for (DateTimeFormatter format : formats) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        return null;
    }

    /**
     * The amount in {@code raw}, or null when it is blank or not a number: a currency symbol and blanks
     * are dropped, the thousands separator of {@code options} is dropped, and a value in parentheses or
     * with a leading or trailing minus is negative.
     */
    public static @Nullable BigDecimal parseAmount(@Nullable String raw, @NonNull ParserOptions options) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = CURRENCY_AND_BLANKS.matcher(raw).replaceAll("");
        boolean negative = false;
        if (cleaned.startsWith("(") && cleaned.endsWith(")") && cleaned.length() > 2) {
            negative = true;
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.endsWith("-") && cleaned.length() > 1) {
            negative = !negative;
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        cleaned = options.decimalFormat() == DecimalFormatOption.DECIMAL_COMMA
                ? cleaned.replace(".", "").replace(',', '.')
                : cleaned.replace(",", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(cleaned);
            return negative ? value.negate() : value;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
