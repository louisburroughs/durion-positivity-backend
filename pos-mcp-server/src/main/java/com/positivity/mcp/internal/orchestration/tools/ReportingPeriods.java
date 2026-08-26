package com.positivity.mcp.internal.orchestration.tools;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/**
 * Maps the LLM-facing {@code period} argument of reporting facades onto the ISO
 * {@code startDate}/{@code endDate} ({@code YYYY-MM-DD}) range parameters the accounting report
 * endpoints actually take (#1519 Wave 2). Accepted forms: {@code YYYY-MM} (a calendar month) and
 * {@code YYYY} (a calendar year); anything else is rejected with a message listing the accepted
 * forms so the model can self-correct.
 */
final class ReportingPeriods {

    private static final Pattern YEAR = Pattern.compile("\\d{4}");
    private static final Pattern YEAR_MONTH = Pattern.compile("\\d{4}-\\d{2}");

    record DateRange(@NonNull String startDate, @NonNull String endDate) {}

    private ReportingPeriods() {}

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
