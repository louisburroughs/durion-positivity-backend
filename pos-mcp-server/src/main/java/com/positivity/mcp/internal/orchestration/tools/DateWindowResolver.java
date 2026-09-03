package com.positivity.mcp.internal.orchestration.tools;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Pure {@code java.time} date-window arithmetic (#1675) — no Spring, no I/O. Three rounds of
 * prompt text (#1664, #1670, #1672) asked the model to compute a relative range's concrete dates
 * itself; single-period rules worked (q01 "last month", q03 "this quarter") but multi-period
 * calendar spans did not (q09 "in the last twelve months" resolved rolling instead of calendar,
 * q12/q15 likewise). The window for a question and a "today" is a pure function, so it moved
 * here: the model's job narrows to classifying the SHAPE from the wording and calling {@link
 * DateWindowFacadeTool#resolveDateWindow} with it; this class does the arithmetic.
 *
 * <p>Rules are ported verbatim from the retired arithmetic bullets of {@code
 * SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT} (see that field's history for the live-run
 * regressions each rule closes):
 *
 * <ul>
 *   <li>{@link Shape#ROLLING} — N units ending on {@code today}, inclusive.
 *   <li>{@link Shape#CURRENT_TO_DATE} — the first day of the period containing {@code today}
 *       through {@code today}; always partial by definition. {@code count} must be 1.
 *   <li>{@link Shape#PRIOR_COMPLETE} — exactly one whole period, the most recent one that has
 *       ended. {@code count} must be 1.
 *   <li>{@link Shape#CALENDAR_SPAN} — N whole periods ending with the most recently completed
 *       one; never anchored to a fixed calendar edge (start of year, quarter, etc).
 * </ul>
 *
 * <p>{@link Unit#DAY} has no complete calendar form (there is no "whole day" boundary beyond the
 * day itself), so it is only valid with {@link Shape#ROLLING}; every calendar shape requires
 * {@link Unit#WEEK}, {@link Unit#MONTH}, {@link Unit#QUARTER}, or {@link Unit#YEAR}. Weeks are
 * ISO (Monday-Sunday); quarters are calendar quarters (Jan-Mar, Apr-Jun, Jul-Sep, Oct-Dec).
 *
 * <p><b>The start is never after the end.</b> The old prompt text carried a defensive fallback for
 * a {@code CALENDAR_SPAN} that "would invert" (fewer than N complete periods existing) — the
 * January case, where treating "this year" as a whole-periods-only span could run its end before
 * its start. That case cannot arise here: {@code CALENDAR_SPAN}'s start is always derived by
 * subtracting whole periods from the current period's start, and {@code CURRENT_TO_DATE} (which
 * "this year" now resolves to) never excludes the partial period in the first place, so it cannot
 * invert either. The invariant holds by construction rather than by a runtime check.
 */
final class DateWindowResolver {

    /** How the window's boundaries relate to the current date and to calendar periods. */
    enum Shape {
        ROLLING,
        CURRENT_TO_DATE,
        PRIOR_COMPLETE,
        CALENDAR_SPAN
    }

    /** The calendar unit the count is expressed in. */
    enum Unit {
        DAY,
        WEEK,
        MONTH,
        QUARTER,
        YEAR
    }

    /** An optional second window to resolve alongside the primary one, for a paired comparison. */
    enum Comparison {
        NONE,
        /** The same shape and length, immediately before the primary window. */
        PRIOR_PERIOD,
        /** The primary window's exact span, shifted back one year. */
        YEAR_EARLIER
    }

    /** One resolved date range plus the sentence a caller should quote to disclose it. */
    record Window(
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate,
            @NonNull String statement) {}

    /** The primary window, its shape, and (when requested) the paired comparison window. */
    record ResolvedWindow(
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate,
            @NonNull Shape shape,
            @NonNull String statement,
            @Nullable Window comparison) {}

    private DateWindowResolver() {}

    /**
     * Resolves a classified relative date range to concrete dates as of {@code today}.
     *
     * @throws IllegalArgumentException if {@code count} is not positive, if {@code unit} is
     *     {@link Unit#DAY} with a shape other than {@link Shape#ROLLING}, or if {@code count} is
     *     not 1 for {@link Shape#CURRENT_TO_DATE} or {@link Shape#PRIOR_COMPLETE} (each names
     *     exactly one period) — every message states what to pass instead so a caller can
     *     self-correct.
     */
    static @NonNull ResolvedWindow resolve(
            @NonNull LocalDate today,
            @NonNull Shape shape,
            @NonNull Unit unit,
            int count,
            @NonNull Comparison comparison) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive (got " + count + ")");
        }
        if (unit == Unit.DAY && shape != Shape.ROLLING) {
            throw new IllegalArgumentException("DAY has no calendar form for shape " + shape
                    + "; pass shape=ROLLING for a day-expressed range, or a calendar unit "
                    + "(WEEK, MONTH, QUARTER, YEAR) for " + shape);
        }
        if ((shape == Shape.CURRENT_TO_DATE || shape == Shape.PRIOR_COMPLETE) && count != 1) {
            throw new IllegalArgumentException(
                    shape + " always names exactly one period; pass count=1, or use CALENDAR_SPAN " + "for a span of "
                            + count + " periods");
        }

        LocalDate start;
        LocalDate end;
        switch (shape) {
            case ROLLING -> {
                start = shiftBack(today, unit, count).plusDays(1);
                end = today;
            }
            case CURRENT_TO_DATE -> {
                start = periodStartContaining(today, unit);
                end = today;
            }
            case PRIOR_COMPLETE -> {
                LocalDate currentPeriodStart = periodStartContaining(today, unit);
                end = currentPeriodStart.minusDays(1);
                start = periodStartContaining(end, unit);
            }
            case CALENDAR_SPAN -> {
                LocalDate currentPeriodStart = periodStartContaining(today, unit);
                end = currentPeriodStart.minusDays(1);
                start = shiftBack(currentPeriodStart, unit, count);
            }
            default -> throw new IllegalStateException("unhandled shape " + shape);
        }

        String statement = statement(shape, unit, count, start, end, today);
        Window comparisonWindow = resolveComparison(comparison, unit, count, start, end);
        return new ResolvedWindow(start, end, shape, statement, comparisonWindow);
    }

    // ── comparison ────────────────────────────────────────────────────────────

    private static @Nullable Window resolveComparison(
            @NonNull Comparison comparison,
            @NonNull Unit unit,
            int count,
            @NonNull LocalDate start,
            @NonNull LocalDate end) {
        return switch (comparison) {
            case NONE -> null;
            case PRIOR_PERIOD -> {
                // Shift only the (always well-defined) start; the comparison end then falls out as
                // the day before the primary window starts. Shifting the primary END instead would
                // be wrong whenever the calendar unit's month lengths differ (e.g. a quarter ending
                // 30 June minus 3 months lands on 30 March, not the true quarter-end 31 March) —
                // the start never has that ambiguity because every shape's start is either a
                // canonical period boundary (day 1, or a Monday) or itself already derived the same
                // way ROLLING derives its own start.
                LocalDate comparisonStart = shiftBack(start, unit, count);
                LocalDate comparisonEnd = start.minusDays(1);
                yield new Window(
                        comparisonStart,
                        comparisonEnd,
                        "prior period: " + comparisonStart + " to " + comparisonEnd + " — the " + unitNoun(unit, count)
                                + " immediately before the primary window");
            }
            case YEAR_EARLIER -> {
                LocalDate comparisonStart = start.minusYears(1);
                LocalDate comparisonEnd = end.minusYears(1);
                yield new Window(
                        comparisonStart,
                        comparisonEnd,
                        "year earlier: " + comparisonStart + " to " + comparisonEnd
                                + " — the same span one year earlier");
            }
        };
    }

    // ── statement text ───────────────────────────────────────────────────────

    private static String statement(
            @NonNull Shape shape,
            @NonNull Unit unit,
            int count,
            @NonNull LocalDate start,
            @NonNull LocalDate end,
            @NonNull LocalDate today) {
        String label =
                switch (shape) {
                    case ROLLING -> "rolling";
                    case CURRENT_TO_DATE -> "current to date";
                    case PRIOR_COMPLETE -> "prior complete";
                    case CALENDAR_SPAN -> "calendar span";
                };
        String clause =
                switch (shape) {
                    case ROLLING -> count + " " + unitNoun(unit, count) + " ending today (" + today + ")";
                    case CURRENT_TO_DATE ->
                        "the current " + unitNoun(unit, 1) + " to date, through today (" + today + ")";
                    case PRIOR_COMPLETE ->
                        "the most recently completed " + unitNoun(unit, 1) + " (" + periodLabel(start, unit) + ")";
                    case CALENDAR_SPAN ->
                        count + " whole " + unitNoun(unit, count)
                                + " ending with the last complete " + unitNoun(unit, 1) + " ("
                                + periodLabel(periodStartContaining(end, unit), unit) + ")";
                };
        return label + ": " + start + " to " + end + " — " + clause;
    }

    private static String periodLabel(@NonNull LocalDate periodStart, @NonNull Unit unit) {
        return switch (unit) {
            case MONTH ->
                periodStart.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + periodStart.getYear();
            case QUARTER -> "Q" + ((periodStart.getMonthValue() - 1) / 3 + 1) + " " + periodStart.getYear();
            case WEEK -> "the week of " + periodStart;
            case YEAR -> String.valueOf(periodStart.getYear());
            case DAY -> periodStart.toString();
        };
    }

    private static String unitNoun(@NonNull Unit unit, int count) {
        String base =
                switch (unit) {
                    case DAY -> "day";
                    case WEEK -> "week";
                    case MONTH -> "month";
                    case QUARTER -> "quarter";
                    case YEAR -> "year";
                };
        return count == 1 ? base : base + "s";
    }

    // ── calendar arithmetic ──────────────────────────────────────────────────

    /** The first day of the period of {@code unit} that contains {@code date}. */
    private static LocalDate periodStartContaining(@NonNull LocalDate date, @NonNull Unit unit) {
        return switch (unit) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
            case QUARTER -> firstDayOfQuarter(date);
            case YEAR -> date.withDayOfYear(1);
        };
    }

    private static LocalDate firstDayOfQuarter(@NonNull LocalDate date) {
        int quarterStartMonth = ((date.getMonthValue() - 1) / 3) * 3 + 1;
        return LocalDate.of(date.getYear(), quarterStartMonth, 1);
    }

    /** {@code date} shifted back {@code count} whole units of {@code unit}. */
    private static LocalDate shiftBack(@NonNull LocalDate date, @NonNull Unit unit, int count) {
        return switch (unit) {
            case DAY -> date.minusDays(count);
            case WEEK -> date.minusWeeks(count);
            case MONTH -> date.minusMonths(count);
            case QUARTER -> date.minusMonths(3L * count);
            case YEAR -> date.minusYears(count);
        };
    }
}
