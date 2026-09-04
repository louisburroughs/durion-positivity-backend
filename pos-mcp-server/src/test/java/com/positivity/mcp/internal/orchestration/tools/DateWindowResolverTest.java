package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.mcp.internal.exception.InvalidToolArgumentException;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Table-driven tests for {@link DateWindowResolver} (#1675). The primary-window cases replay
 * every real {@code window.resolved_range} from {@code eval/analytics-gate/QUESTIONS.json} that
 * names a single deterministic range, resolved for the gate's as-of date 2026-09-03, plus the
 * edge cases the plan calls out by name (January wrap, a leap day, an ISO week boundary, rejected
 * inputs). DateWindowResolver.Comparison cases (q07 PRIOR_PERIOD, q15 YEAR_EARLIER) and the count/unit rejections
 * each get their own focused test — the shapes of their assertions differ from the plain
 * start/end table.
 */
class DateWindowResolverTest {

    private static final LocalDate GATE_TODAY = LocalDate.of(2026, 9, 3);

    static Stream<Arguments> primaryWindowCases() {
        return Stream.of(
                // q01: "last month" as of 2026-09-03.
                Arguments.of(
                        "q01 last month",
                        GATE_TODAY,
                        DateWindowResolver.Shape.PRIOR_COMPLETE,
                        DateWindowResolver.Unit.MONTH,
                        1,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31)),
                // q02: "over the last three months" ground truth buckets three complete calendar months.
                Arguments.of(
                        "q02 three-month calendar span",
                        GATE_TODAY,
                        DateWindowResolver.Shape.CALENDAR_SPAN,
                        DateWindowResolver.Unit.MONTH,
                        3,
                        LocalDate.of(2026, 6, 1),
                        LocalDate.of(2026, 8, 31)),
                // q03: "this quarter" as of 2026-09-03.
                Arguments.of(
                        "q03 this quarter",
                        GATE_TODAY,
                        DateWindowResolver.Shape.CURRENT_TO_DATE,
                        DateWindowResolver.Unit.QUARTER,
                        1,
                        LocalDate.of(2026, 7, 1),
                        GATE_TODAY),
                // q04 / q12: "in the last six months".
                Arguments.of(
                        "q04/q12 six-month calendar span",
                        GATE_TODAY,
                        DateWindowResolver.Shape.CALENDAR_SPAN,
                        DateWindowResolver.Unit.MONTH,
                        6,
                        LocalDate.of(2026, 3, 1),
                        LocalDate.of(2026, 8, 31)),
                // q09: "in the last twelve months".
                Arguments.of(
                        "q09 twelve-month calendar span",
                        GATE_TODAY,
                        DateWindowResolver.Shape.CALENDAR_SPAN,
                        DateWindowResolver.Unit.MONTH,
                        12,
                        LocalDate.of(2025, 9, 1),
                        LocalDate.of(2026, 8, 31)),
                // q11: "for the last twelve weeks" — twelve ISO Mon-Sun weeks.
                Arguments.of(
                        "q11 twelve-week calendar span",
                        GATE_TODAY,
                        DateWindowResolver.Shape.CALENDAR_SPAN,
                        DateWindowResolver.Unit.WEEK,
                        12,
                        LocalDate.of(2026, 6, 8),
                        LocalDate.of(2026, 8, 30)),
                // The pre-#1675 prompt's own illustration ("over the last six months would be 2026-03-04
                // to 2026-09-03"), now produced by the resolver instead of by the model.
                Arguments.of(
                        "rolling six months (former prompt illustration)",
                        GATE_TODAY,
                        DateWindowResolver.Shape.ROLLING,
                        DateWindowResolver.Unit.MONTH,
                        6,
                        LocalDate.of(2026, 3, 4),
                        GATE_TODAY),
                Arguments.of(
                        "rolling two weeks",
                        GATE_TODAY,
                        DateWindowResolver.Shape.ROLLING,
                        DateWindowResolver.Unit.WEEK,
                        2,
                        LocalDate.of(2026, 8, 21),
                        GATE_TODAY),
                // "This year" on 2026-01-15 is CURRENT_TO_DATE and therefore partial, never a whole prior
                // year — the #1675 fix for the old January-inversion concern.
                Arguments.of(
                        "this year on 15 January",
                        LocalDate.of(2026, 1, 15),
                        DateWindowResolver.Shape.CURRENT_TO_DATE,
                        DateWindowResolver.Unit.YEAR,
                        1,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 15)),
                // A six-month CALENDAR_SPAN asked in January wraps into the prior year without ever
                // inverting (start is always derived by subtracting whole periods from the current
                // period's start, so start <= end by construction).
                Arguments.of(
                        "six-month calendar span on 15 January",
                        LocalDate.of(2026, 1, 15),
                        DateWindowResolver.Shape.CALENDAR_SPAN,
                        DateWindowResolver.Unit.MONTH,
                        6,
                        LocalDate.of(2025, 7, 1),
                        LocalDate.of(2025, 12, 31)),
                // "Last month" resolved the day after a leap February must keep all 29 days of it.
                Arguments.of(
                        "last month is a leap February",
                        LocalDate.of(2024, 3, 1),
                        DateWindowResolver.Shape.PRIOR_COMPLETE,
                        DateWindowResolver.Unit.MONTH,
                        1,
                        LocalDate.of(2024, 2, 1),
                        LocalDate.of(2024, 2, 29)),
                // Same query one year later (non-leap) keeps February to 28 days.
                Arguments.of(
                        "last month is a non-leap February",
                        LocalDate.of(2023, 3, 1),
                        DateWindowResolver.Shape.PRIOR_COMPLETE,
                        DateWindowResolver.Unit.MONTH,
                        1,
                        LocalDate.of(2023, 2, 1),
                        LocalDate.of(2023, 2, 28)),
                // 2026-09-09 is a Wednesday; "last week" is the previous ISO Monday-Sunday week.
                Arguments.of(
                        "last week asked on a Wednesday",
                        LocalDate.of(2026, 9, 9),
                        DateWindowResolver.Shape.PRIOR_COMPLETE,
                        DateWindowResolver.Unit.WEEK,
                        1,
                        LocalDate.of(2026, 8, 31),
                        LocalDate.of(2026, 9, 6)),
                // "Last quarter" and "last year" for completeness of the calendar unit set.
                Arguments.of(
                        "last quarter",
                        GATE_TODAY,
                        DateWindowResolver.Shape.PRIOR_COMPLETE,
                        DateWindowResolver.Unit.QUARTER,
                        1,
                        LocalDate.of(2026, 4, 1),
                        LocalDate.of(2026, 6, 30)),
                Arguments.of(
                        "last year",
                        GATE_TODAY,
                        DateWindowResolver.Shape.PRIOR_COMPLETE,
                        DateWindowResolver.Unit.YEAR,
                        1,
                        LocalDate.of(2025, 1, 1),
                        LocalDate.of(2025, 12, 31)),
                Arguments.of(
                        "in the last three years (calendar span)",
                        GATE_TODAY,
                        DateWindowResolver.Shape.CALENDAR_SPAN,
                        DateWindowResolver.Unit.YEAR,
                        3,
                        LocalDate.of(2023, 1, 1),
                        LocalDate.of(2025, 12, 31)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("primaryWindowCases")
    @DisplayName("resolve computes the primary window")
    void resolve_computesPrimaryWindow(
            String description,
            LocalDate today,
            DateWindowResolver.Shape shape,
            DateWindowResolver.Unit unit,
            int count,
            LocalDate expectedStart,
            LocalDate expectedEnd) {
        DateWindowResolver.ResolvedWindow resolved =
                DateWindowResolver.resolve(today, shape, unit, count, DateWindowResolver.Comparison.NONE);

        assertThat(resolved.startDate()).as(description).isEqualTo(expectedStart);
        assertThat(resolved.endDate()).as(description).isEqualTo(expectedEnd);
        assertThat(resolved.shape()).isEqualTo(shape);
        assertThat(resolved.comparison()).as("no comparison was requested").isNull();
        // Invariant this class replaces the old prompt-level fallback with: never inverted.
        assertThat(resolved.startDate()).isBeforeOrEqualTo(resolved.endDate());
    }

    @Test
    @DisplayName("q07: twelve-month calendar span compared with PRIOR_PERIOD")
    void resolve_q07PriorPeriodComparison() {
        DateWindowResolver.ResolvedWindow resolved = DateWindowResolver.resolve(
                GATE_TODAY,
                DateWindowResolver.Shape.CALENDAR_SPAN,
                DateWindowResolver.Unit.MONTH,
                12,
                DateWindowResolver.Comparison.PRIOR_PERIOD);

        assertThat(resolved.startDate()).isEqualTo(LocalDate.of(2025, 9, 1));
        assertThat(resolved.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(resolved.comparison()).isNotNull();
        assertThat(resolved.comparison().startDate()).isEqualTo(LocalDate.of(2024, 9, 1));
        assertThat(resolved.comparison().endDate()).isEqualTo(LocalDate.of(2025, 8, 31));
    }

    @Test
    @DisplayName("q15: six-month calendar span compared with the same six months last year")
    void resolve_q15YearEarlierComparison() {
        DateWindowResolver.ResolvedWindow resolved = DateWindowResolver.resolve(
                GATE_TODAY,
                DateWindowResolver.Shape.CALENDAR_SPAN,
                DateWindowResolver.Unit.MONTH,
                6,
                DateWindowResolver.Comparison.YEAR_EARLIER);

        assertThat(resolved.startDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(resolved.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(resolved.comparison()).isNotNull();
        assertThat(resolved.comparison().startDate()).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(resolved.comparison().endDate()).isEqualTo(LocalDate.of(2025, 8, 31));
    }

    @Test
    @DisplayName("PRIOR_PERIOD offsets a one-quarter PRIOR_COMPLETE window by exactly one quarter")
    void resolve_priorPeriodComparisonForQuarter() {
        DateWindowResolver.ResolvedWindow resolved = DateWindowResolver.resolve(
                GATE_TODAY,
                DateWindowResolver.Shape.PRIOR_COMPLETE,
                DateWindowResolver.Unit.QUARTER,
                1,
                DateWindowResolver.Comparison.PRIOR_PERIOD);

        assertThat(resolved.startDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(resolved.endDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(resolved.comparison().startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(resolved.comparison().endDate()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("PRIOR_PERIOD offsets a one-month PRIOR_COMPLETE window by exactly one month")
    void resolve_priorPeriodComparisonForMonth() {
        DateWindowResolver.ResolvedWindow resolved = DateWindowResolver.resolve(
                GATE_TODAY,
                DateWindowResolver.Shape.PRIOR_COMPLETE,
                DateWindowResolver.Unit.MONTH,
                1,
                DateWindowResolver.Comparison.PRIOR_PERIOD);

        assertThat(resolved.startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(resolved.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(resolved.comparison().startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(resolved.comparison().endDate()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    @DisplayName("YEAR_EARLIER clamps a leap day (29 February) to 28 February in the non-leap comparison year")
    void resolve_yearEarlierClampsLeapDay() {
        LocalDate leapDay = LocalDate.of(2024, 2, 29);

        DateWindowResolver.ResolvedWindow resolved = DateWindowResolver.resolve(
                leapDay,
                DateWindowResolver.Shape.ROLLING,
                DateWindowResolver.Unit.DAY,
                1,
                DateWindowResolver.Comparison.YEAR_EARLIER);

        assertThat(resolved.startDate()).isEqualTo(leapDay);
        assertThat(resolved.endDate()).isEqualTo(leapDay);
        assertThat(resolved.comparison().startDate()).isEqualTo(LocalDate.of(2023, 2, 28));
        assertThat(resolved.comparison().endDate()).isEqualTo(LocalDate.of(2023, 2, 28));
    }

    @Test
    @DisplayName("statement quotes explicit dates and the calendar-span period label")
    void resolve_statementIsHumanReadableAndQuotable() {
        DateWindowResolver.ResolvedWindow resolved = DateWindowResolver.resolve(
                GATE_TODAY,
                DateWindowResolver.Shape.CALENDAR_SPAN,
                DateWindowResolver.Unit.MONTH,
                6,
                DateWindowResolver.Comparison.NONE);

        assertThat(resolved.statement())
                .isEqualTo("calendar span: 2026-03-01 to 2026-08-31 — 6 whole months ending with the last "
                        + "complete month (August 2026)");
    }

    @Test
    @DisplayName("comparison statement is human-readable and quotable")
    void resolve_comparisonStatementIsHumanReadable() {
        DateWindowResolver.ResolvedWindow resolved = DateWindowResolver.resolve(
                GATE_TODAY,
                DateWindowResolver.Shape.CALENDAR_SPAN,
                DateWindowResolver.Unit.MONTH,
                6,
                DateWindowResolver.Comparison.YEAR_EARLIER);

        assertThat(resolved.comparison().statement())
                .isEqualTo("year earlier: 2025-03-01 to 2025-08-31 — the same span one year earlier");
    }

    @Test
    @DisplayName("DAY is rejected for every shape but ROLLING, and the message names the fix")
    void resolve_rejectsDayWithNonRollingShape() {
        assertThatExceptionOfType(InvalidToolArgumentException.class)
                .isThrownBy(() -> DateWindowResolver.resolve(
                        GATE_TODAY,
                        DateWindowResolver.Shape.CALENDAR_SPAN,
                        DateWindowResolver.Unit.DAY,
                        90,
                        DateWindowResolver.Comparison.NONE))
                .withMessageContaining("DAY has no calendar form")
                .withMessageContaining("ROLLING");
        assertThatExceptionOfType(InvalidToolArgumentException.class)
                .isThrownBy(() -> DateWindowResolver.resolve(
                        GATE_TODAY,
                        DateWindowResolver.Shape.CURRENT_TO_DATE,
                        DateWindowResolver.Unit.DAY,
                        1,
                        DateWindowResolver.Comparison.NONE));
        assertThatExceptionOfType(InvalidToolArgumentException.class)
                .isThrownBy(() -> DateWindowResolver.resolve(
                        GATE_TODAY,
                        DateWindowResolver.Shape.PRIOR_COMPLETE,
                        DateWindowResolver.Unit.DAY,
                        1,
                        DateWindowResolver.Comparison.NONE));
    }

    @Test
    @DisplayName("DAY is accepted for ROLLING")
    void resolve_acceptsDayWithRollingShape() {
        DateWindowResolver.ResolvedWindow resolved = DateWindowResolver.resolve(
                GATE_TODAY,
                DateWindowResolver.Shape.ROLLING,
                DateWindowResolver.Unit.DAY,
                90,
                DateWindowResolver.Comparison.NONE);

        assertThat(resolved.startDate()).isEqualTo(GATE_TODAY.minusDays(89));
        assertThat(resolved.endDate()).isEqualTo(GATE_TODAY);
    }

    @Test
    @DisplayName("count <= 0 is rejected for every shape")
    void resolve_rejectsNonPositiveCount() {
        assertThatExceptionOfType(InvalidToolArgumentException.class)
                .isThrownBy(() -> DateWindowResolver.resolve(
                        GATE_TODAY,
                        DateWindowResolver.Shape.ROLLING,
                        DateWindowResolver.Unit.MONTH,
                        0,
                        DateWindowResolver.Comparison.NONE))
                .withMessageContaining("count must be positive");
        assertThatExceptionOfType(InvalidToolArgumentException.class)
                .isThrownBy(() -> DateWindowResolver.resolve(
                        GATE_TODAY,
                        DateWindowResolver.Shape.CALENDAR_SPAN,
                        DateWindowResolver.Unit.MONTH,
                        -3,
                        DateWindowResolver.Comparison.NONE))
                .withMessageContaining("count must be positive");
    }

    @Test
    @DisplayName("CURRENT_TO_DATE and PRIOR_COMPLETE reject a count other than 1, naming CALENDAR_SPAN instead")
    void resolve_rejectsMultiCountForSinglePeriodShapes() {
        assertThatExceptionOfType(InvalidToolArgumentException.class)
                .isThrownBy(() -> DateWindowResolver.resolve(
                        GATE_TODAY,
                        DateWindowResolver.Shape.CURRENT_TO_DATE,
                        DateWindowResolver.Unit.MONTH,
                        2,
                        DateWindowResolver.Comparison.NONE))
                .withMessageContaining("CALENDAR_SPAN");
        assertThatExceptionOfType(InvalidToolArgumentException.class)
                .isThrownBy(() -> DateWindowResolver.resolve(
                        GATE_TODAY,
                        DateWindowResolver.Shape.PRIOR_COMPLETE,
                        DateWindowResolver.Unit.QUARTER,
                        3,
                        DateWindowResolver.Comparison.NONE))
                .withMessageContaining("CALENDAR_SPAN");
    }

    // ── ABSOLUTE (#1684) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveNamed expands a named calendar year to the whole year")
    void resolveNamed_year() {
        DateWindowResolver.ResolvedWindow window = DateWindowResolver.resolveNamed("2025");

        assertThat(window.startDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(window.endDate()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(window.shape()).isEqualTo(DateWindowResolver.Shape.ABSOLUTE);
        assertThat(window.statement()).contains("absolute").contains("named calendar year 2025");
        assertThat(window.comparison()).isNull();
    }

    @Test
    @DisplayName("resolveNamed expands a named calendar month to that month, including a leap February")
    void resolveNamed_month() {
        assertThat(DateWindowResolver.resolveNamed("2026-07").startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(DateWindowResolver.resolveNamed("2026-07").endDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(DateWindowResolver.resolveNamed("2024-02").endDate()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(DateWindowResolver.resolveNamed("2026-02").endDate()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    @DisplayName("resolveNamed expands every named calendar quarter, case-insensitively")
    void resolveNamed_quarter() {
        assertThat(DateWindowResolver.resolveNamed("2026-Q1").startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(DateWindowResolver.resolveNamed("2026-Q1").endDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(DateWindowResolver.resolveNamed("2026-q3").startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(DateWindowResolver.resolveNamed("2026-q3").endDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(DateWindowResolver.resolveNamed("2026-Q4").endDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("a named period is never clipped to today, so a period still running returns its full extent")
    void resolveNamed_isNotClippedToToday() {
        // The whole reason ABSOLUTE exists: "2026" asked in September is the calendar year, while
        // "this year" is CURRENT_TO_DATE and ends today. Clipping here would erase that distinction
        // and silently reintroduce the ambiguity the removed `period` shortcut had.
        DateWindowResolver.ResolvedWindow named = DateWindowResolver.resolveNamed("2026");
        DateWindowResolver.ResolvedWindow toDate = DateWindowResolver.resolve(
                LocalDate.of(2026, 9, 4),
                DateWindowResolver.Shape.CURRENT_TO_DATE,
                DateWindowResolver.Unit.YEAR,
                1,
                DateWindowResolver.Comparison.NONE);

        assertThat(named.endDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(toDate.endDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(named.startDate()).isEqualTo(toDate.startDate());
    }

    @Test
    @DisplayName("resolveNamed trims whitespace before matching")
    void resolveNamed_trimsWhitespace() {
        assertThat(DateWindowResolver.resolveNamed("  2025 ").startDate()).isEqualTo(LocalDate.of(2025, 1, 1));
    }

    @ParameterizedTest
    @DisplayName("resolveNamed rejects a form it does not support and names the three that it does")
    @ValueSource(strings = {"Q2-2026", "2026-13", "26", "last month", "2026/07", "2026-Q5", ""})
    void resolveNamed_rejectsUnsupportedForms(String period) {
        assertThatThrownBy(() -> DateWindowResolver.resolveNamed(period))
                .isInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("YYYY");
    }

    @Test
    @DisplayName("resolve rejects ABSOLUTE and redirects to resolveNamedPeriod")
    void resolve_rejectsAbsoluteShape() {
        assertThatThrownBy(() -> DateWindowResolver.resolve(
                        LocalDate.of(2026, 9, 4),
                        DateWindowResolver.Shape.ABSOLUTE,
                        DateWindowResolver.Unit.YEAR,
                        1,
                        DateWindowResolver.Comparison.NONE))
                .isInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("resolveNamedPeriod");
    }

    @Test
    @DisplayName("PRIOR_PERIOD against a CURRENT_TO_DATE window compares the same partial span (#1703)")
    void resolve_currentToDateWithPriorPeriod_comparesEqualPartialSpans() {
        // The DATE_WINDOW contract this module ships says: "against a partial current year means the
        // SAME partial span one year earlier, never a complete prior year against an incomplete
        // current one." PRIOR_PERIOD did the opposite — "this month to date" on 2026-09-04 compared
        // 4 days against a whole 31-day August, which reads as a collapse in every metric.
        DateWindowResolver.ResolvedWindow window = DateWindowResolver.resolve(
                LocalDate.of(2026, 9, 4),
                DateWindowResolver.Shape.CURRENT_TO_DATE,
                DateWindowResolver.Unit.MONTH,
                1,
                DateWindowResolver.Comparison.PRIOR_PERIOD);

        assertThat(window.startDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(window.endDate()).isEqualTo(LocalDate.of(2026, 9, 4));

        DateWindowResolver.Window comparison = window.comparison();
        assertThat(comparison).isNotNull();
        assertThat(comparison.startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(comparison.endDate()).isEqualTo(LocalDate.of(2026, 8, 4));
    }

    @Test
    @DisplayName("PRIOR_PERIOD against a whole-period window still compares whole periods")
    void resolve_priorCompleteWithPriorPeriod_isUnchanged() {
        // The fix must not disturb the shapes where a whole prior period IS the right comparison.
        DateWindowResolver.ResolvedWindow window = DateWindowResolver.resolve(
                LocalDate.of(2026, 9, 4),
                DateWindowResolver.Shape.PRIOR_COMPLETE,
                DateWindowResolver.Unit.MONTH,
                1,
                DateWindowResolver.Comparison.PRIOR_PERIOD);

        assertThat(window.startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(window.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(window.comparison()).isNotNull();
        assertThat(window.comparison().startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(window.comparison().endDate()).isEqualTo(LocalDate.of(2026, 7, 31));
    }
}
