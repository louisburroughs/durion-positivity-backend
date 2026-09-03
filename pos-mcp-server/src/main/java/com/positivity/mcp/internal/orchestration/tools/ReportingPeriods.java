package com.positivity.mcp.internal.orchestration.tools;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Maps the LLM-facing {@code period} / {@code startDate}+{@code endDate} arguments of reporting
 * facades onto the ISO {@code startDate}/{@code endDate} ({@code YYYY-MM-DD}) range parameters
 * the accounting report endpoints actually take (#1519 Wave 2; range form added #1677 so these
 * tools can take {@code resolveDateWindow}'s output directly instead of being limited to a single
 * whole month or year).
 *
 * <p>{@link #resolve} accepts exactly one of two forms: {@code period} alone ({@code YYYY-MM} for
 * a calendar month or {@code YYYY} for a calendar year — a shortcut for a single whole period), or
 * both {@code startDate} and {@code endDate} (ISO {@code YYYY-MM-DD}, inclusive, from the
 * resolver's own {@code startDate}/{@code endDate}). Any other combination — neither, both forms
 * at once, one date without the other, a malformed date, or {@code startDate} after {@code
 * endDate} — is rejected with a message stating which form to pass so the model can self-correct.
 */
final class ReportingPeriods {

    private static final Pattern YEAR = Pattern.compile("\\d{4}");
    private static final Pattern YEAR_MONTH = Pattern.compile("\\d{4}-\\d{2}");

    record DateRange(@NonNull String startDate, @NonNull String endDate) {}

    private ReportingPeriods() {}

    /**
     * Resolves either the {@code period} shortcut or an explicit {@code startDate}/{@code
     * endDate} range — exactly one of the two forms must be supplied.
     *
     * @throws IllegalArgumentException if neither form, both forms, only one of startDate/endDate,
     *     a malformed period or date, or an inverted startDate/endDate is supplied
     */
    static @NonNull DateRange resolve(@Nullable String period, @Nullable String startDate, @Nullable String endDate) {
        boolean hasPeriod = period != null && !period.isBlank();
        boolean hasStart = startDate != null && !startDate.isBlank();
        boolean hasEnd = endDate != null && !endDate.isBlank();

        if (hasPeriod) {
            if (hasStart || hasEnd) {
                throw new IllegalArgumentException("Pass either period on its own, or both startDate and endDate "
                        + "— not both forms together; got period='" + period + "' together with startDate/endDate");
            }
            return toDateRange(period);
        }
        if (hasStart != hasEnd) {
            throw new IllegalArgumentException("startDate and endDate must be given together (got only "
                    + (hasStart ? "startDate" : "endDate")
                    + "); pass both, ISO YYYY-MM-DD, or use period instead for a single month/year");
        }
        if (!hasStart) {
            throw new IllegalArgumentException("Pass either period (a single calendar month as YYYY-MM or year as "
                    + "YYYY) or both startDate and endDate (ISO YYYY-MM-DD, from resolveDateWindow)");
        }
        LocalDate start = parseDate("startDate", startDate);
        LocalDate end = parseDate("endDate", endDate);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startDate '" + start + "' must not be after endDate '" + end + "'");
        }
        return new DateRange(start.toString(), end.toString());
    }

    private static @NonNull LocalDate parseDate(@NonNull String paramName, @NonNull String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "Invalid " + paramName + " '" + value + "': pass an ISO date in YYYY-MM-DD form (e.g. 2026-06-30)",
                    exception);
        }
    }

    static @NonNull DateRange toDateRange(@NonNull String period) {
        String trimmed = period.trim();
        if (YEAR.matcher(trimmed).matches()) {
            return new DateRange(trimmed + "-01-01", trimmed + "-12-31");
        }
        if (YEAR_MONTH.matcher(trimmed).matches()) {
            try {
                YearMonth month = YearMonth.parse(trimmed);
                return new DateRange(
                        month.atDay(1).toString(), month.atEndOfMonth().toString());
            } catch (DateTimeException exception) {
                throw invalidPeriod(period, exception);
            }
        }
        throw invalidPeriod(period, null);
    }

    private static IllegalArgumentException invalidPeriod(String period, Throwable cause) {
        return new IllegalArgumentException(
                "Unsupported period '" + period
                        + "': pass a calendar month as YYYY-MM (e.g. 2026-05) or a calendar year as YYYY (e.g. 2026)",
                cause);
    }
}
