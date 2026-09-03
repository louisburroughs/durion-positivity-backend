package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReportingPeriods}. {@link ReportingPeriods#resolve} is the shared
 * mechanism the period-only facade tools moved to (#1677) so a multi-month {@code
 * resolveDateWindow} window can be passed to them in one call, instead of being limited to a
 * single whole calendar month or year.
 */
class ReportingPeriodsTest {

    @Test
    @DisplayName("resolve maps a YYYY-MM period to the calendar month, same as toDateRange")
    void resolve_periodFormMonth_mapsToCalendarMonth() {
        ReportingPeriods.DateRange range = ReportingPeriods.resolve("2026-06", null, null);

        assertThat(range.startDate()).isEqualTo("2026-06-01");
        assertThat(range.endDate()).isEqualTo("2026-06-30");
    }

    @Test
    @DisplayName("resolve maps a YYYY period to the calendar year, same as toDateRange")
    void resolve_periodFormYear_mapsToCalendarYear() {
        ReportingPeriods.DateRange range = ReportingPeriods.resolve("2026", null, null);

        assertThat(range.startDate()).isEqualTo("2026-01-01");
        assertThat(range.endDate()).isEqualTo("2026-12-31");
    }

    @Test
    @DisplayName("resolve passes an explicit startDate/endDate range through unchanged")
    void resolve_rangeForm_passesDatesThrough() {
        ReportingPeriods.DateRange range = ReportingPeriods.resolve(null, "2025-07-01", "2026-06-30");

        assertThat(range.startDate()).isEqualTo("2025-07-01");
        assertThat(range.endDate()).isEqualTo("2026-06-30");
    }

    @Test
    @DisplayName("resolve accepts a single-day range (startDate equal to endDate)")
    void resolve_rangeForm_acceptsEqualStartAndEnd() {
        ReportingPeriods.DateRange range = ReportingPeriods.resolve(null, "2026-06-15", "2026-06-15");

        assertThat(range.startDate()).isEqualTo("2026-06-15");
        assertThat(range.endDate()).isEqualTo("2026-06-15");
    }

    @Test
    @DisplayName("resolve rejects both period and a date range being supplied together")
    void resolve_rejectsBothFormsTogether() {
        assertThatThrownBy(() -> ReportingPeriods.resolve("2026-06", "2025-07-01", "2026-06-30"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period")
                .hasMessageContaining("startDate");
    }

    @Test
    @DisplayName("resolve rejects when neither period nor a date range is supplied")
    void resolve_rejectsNeitherFormSupplied() {
        assertThatThrownBy(() -> ReportingPeriods.resolve(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period")
                .hasMessageContaining("startDate");
    }

    @Test
    @DisplayName("resolve rejects startDate without endDate")
    void resolve_rejectsStartDateWithoutEndDate() {
        assertThatThrownBy(() -> ReportingPeriods.resolve(null, "2026-06-01", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startDate")
                .hasMessageContaining("endDate");
    }

    @Test
    @DisplayName("resolve rejects endDate without startDate")
    void resolve_rejectsEndDateWithoutStartDate() {
        assertThatThrownBy(() -> ReportingPeriods.resolve(null, null, "2026-06-30"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startDate")
                .hasMessageContaining("endDate");
    }

    @Test
    @DisplayName("resolve rejects an inverted startDate/endDate range")
    void resolve_rejectsInvertedDateRange() {
        assertThatThrownBy(() -> ReportingPeriods.resolve(null, "2026-06-30", "2026-06-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startDate")
                .hasMessageContaining("endDate");
    }

    @Test
    @DisplayName("resolve rejects a malformed startDate")
    void resolve_rejectsMalformedStartDate() {
        assertThatThrownBy(() -> ReportingPeriods.resolve(null, "2026/06/01", "2026-06-30"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startDate")
                .hasMessageContaining("YYYY-MM-DD");
    }

    @Test
    @DisplayName("resolve rejects a malformed endDate")
    void resolve_rejectsMalformedEndDate() {
        assertThatThrownBy(() -> ReportingPeriods.resolve(null, "2026-06-01", "not-a-date"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endDate")
                .hasMessageContaining("YYYY-MM-DD");
    }

    @Test
    @DisplayName("resolve rejects a malformed period the same way toDateRange does")
    void resolve_rejectsMalformedPeriod() {
        assertThatThrownBy(() -> ReportingPeriods.resolve("2025-Q1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM")
                .hasMessageContaining("YYYY");
    }

    @Test
    @DisplayName("toDateRange still maps a YYYY-MM period directly, unchanged")
    void toDateRange_periodFormMonth_unchanged() {
        ReportingPeriods.DateRange range = ReportingPeriods.toDateRange("2026-06");

        assertThat(range.startDate()).isEqualTo("2026-06-01");
        assertThat(range.endDate()).isEqualTo("2026-06-30");
    }
}
