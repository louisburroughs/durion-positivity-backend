package com.positivity.mcp.internal.orchestration.tools;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Validates the {@code startDate}/{@code endDate} ({@code YYYY-MM-DD}) range parameters the
 * accounting report endpoints take, as supplied by {@code DateWindowFacadeTool} (#1519 Wave 2;
 * range form added #1677).
 *
 * <p><strong>The {@code period} shortcut was removed in #1684.</strong> It accepted a label
 * ({@code YYYY} or {@code YYYY-MM}) and expanded it to a range inside the reporting tool, which
 * made it a hole in the date-window contract: a window reached the downstream call without any
 * shape ever being classified, so nothing distinguished {@code period="2026"} meaning the named
 * year 2026 from the same argument standing in for "this year", which is
 * {@code CURRENT_TO_DATE} and a different window. Because the expansion happened here rather
 * than in the resolver, neither reading was recorded at the tool boundary and neither could be
 * graded. Both readings now have a resolver call that names them —
 * {@code resolveNamedPeriod("2026")} and {@code resolveDateWindow(CURRENT_TO_DATE, YEAR, 1)} —
 * so requiring the dates here costs no expressiveness and makes the shape observable on every
 * dated call.
 */
final class ReportingPeriods {

    record DateRange(@NonNull String startDate, @NonNull String endDate) {}

    private ReportingPeriods() {}

    /**
     * Validates an explicit inclusive {@code startDate}/{@code endDate} range.
     *
     * @throws IllegalArgumentException if either date is missing, malformed, or if
     *     {@code startDate} is after {@code endDate}; every message names the resolver tool to
     *     call so the model can self-correct rather than guess a range.
     */
    static @NonNull DateRange resolve(@Nullable String startDate, @Nullable String endDate) {
        boolean hasStart = startDate != null && !startDate.isBlank();
        boolean hasEnd = endDate != null && !endDate.isBlank();

        if (!hasStart || !hasEnd) {
            throw new IllegalArgumentException("startDate and endDate are both required (got "
                    + (hasStart ? "only startDate" : hasEnd ? "only endDate" : "neither")
                    + "); call resolveDateWindow for a range relative to today (\"last month\", \"in the "
                    + "last six months\") or resolveNamedPeriod for a period the question names outright "
                    + "(\"2025\", \"2026-07\", \"2026-Q3\"), then copy its startDate/endDate verbatim");
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
            // Names the resolvers, not just the format. The likeliest way to reach this branch is a
            // model still carrying the removed `period` contract and putting its label ("2025",
            // "2026-07") into startDate — for which "pass YYYY-MM-DD" is true but not actionable,
            // since the caller wants a whole named period and needs to be told where to get one.
            throw new IllegalArgumentException(
                    "Invalid " + paramName + " '" + value + "': pass an ISO date in YYYY-MM-DD form "
                            + "(e.g. 2026-06-30). For a whole named period such as '2025', '2026-07' or "
                            + "'2026-Q3', call resolveNamedPeriod and copy its startDate/endDate; for a range "
                            + "relative to today, call resolveDateWindow.",
                    exception);
        }
    }
}
