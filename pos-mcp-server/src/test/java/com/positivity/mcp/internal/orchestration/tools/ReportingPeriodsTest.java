package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ReportingPeriods} after #1684 removed the {@code period} shortcut.
 *
 * <p>The removed cases were the point of the change: a label like {@code "2026"} used to expand to
 * a range inside the reporting tool, which let a window reach a downstream call without any shape
 * ever being classified or logged. There is no test for that form here because the parameter no
 * longer exists — the compiler enforces it, which is the strongest form the assertion can take.
 * What remains to test is that the replacement path fails loudly and points at the two tools that
 * produce a valid range.
 */
class ReportingPeriodsTest {

    @Test
    @DisplayName("accepts an explicit inclusive range and echoes it back normalised")
    void resolve_acceptsExplicitRange() {
        ReportingPeriods.DateRange range = ReportingPeriods.resolve("2026-03-01", "2026-08-31");

        assertThat(range.startDate()).isEqualTo("2026-03-01");
        assertThat(range.endDate()).isEqualTo("2026-08-31");
    }

    @Test
    @DisplayName("accepts a single-day range")
    void resolve_acceptsSingleDayRange() {
        ReportingPeriods.DateRange range = ReportingPeriods.resolve("2026-03-01", "2026-03-01");

        assertThat(range.startDate()).isEqualTo("2026-03-01");
        assertThat(range.endDate()).isEqualTo("2026-03-01");
    }

    @Test
    @DisplayName("trims surrounding whitespace rather than rejecting the date")
    void resolve_trimsWhitespace() {
        ReportingPeriods.DateRange range = ReportingPeriods.resolve("  2026-03-01 ", " 2026-08-31  ");

        assertThat(range.startDate()).isEqualTo("2026-03-01");
        assertThat(range.endDate()).isEqualTo("2026-08-31");
    }

    @Test
    @DisplayName("names both resolver tools when neither date is supplied, so the model can self-correct")
    void resolve_rejectsMissingRange() {
        assertThatThrownBy(() -> ReportingPeriods.resolve(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startDate and endDate are both required")
                .hasMessageContaining("neither")
                .hasMessageContaining("resolveDateWindow")
                .hasMessageContaining("resolveNamedPeriod");
    }

    @Test
    @DisplayName("says which of the two dates was supplied when only one is")
    void resolve_rejectsUnpairedDate() {
        assertThatThrownBy(() -> ReportingPeriods.resolve("2026-03-01", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only startDate");

        assertThatThrownBy(() -> ReportingPeriods.resolve(null, "2026-08-31"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only endDate");
    }

    @Test
    @DisplayName("treats a blank date as absent rather than parsing it")
    void resolve_rejectsBlankDate() {
        assertThatThrownBy(() -> ReportingPeriods.resolve("   ", "2026-08-31"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only endDate");
    }

    @Test
    @DisplayName("rejects a malformed date with the expected form in the message")
    void resolve_rejectsMalformedDate() {
        assertThatThrownBy(() -> ReportingPeriods.resolve("03/01/2026", "2026-08-31"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startDate")
                .hasMessageContaining("YYYY-MM-DD");
    }

    @Test
    @DisplayName("a period label passed as a date names resolveNamedPeriod, not just the ISO format")
    void resolve_periodLabelInDateSlotPointsAtTheResolver() {
        // The likeliest way a model still carrying the removed `period` contract reaches this class:
        // it reuses the label it used to pass as `period`. "Pass YYYY-MM-DD" is true but not
        // actionable for someone who wants a whole named period.
        for (String label : new String[] {"2025", "2026-07", "2026-Q3"}) {
            assertThatThrownBy(() -> ReportingPeriods.resolve(label, label))
                    .as("label '%s'", label)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resolveNamedPeriod")
                    .hasMessageContaining("resolveDateWindow");
        }
    }

    @Test
    @DisplayName("rejects an inverted range")
    void resolve_rejectsInvertedRange() {
        assertThatThrownBy(() -> ReportingPeriods.resolve("2026-08-31", "2026-03-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be after");
    }
}
