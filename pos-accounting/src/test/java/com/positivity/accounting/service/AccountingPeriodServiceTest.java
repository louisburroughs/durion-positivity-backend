package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.accounting.internal.service.AccountingPeriodServiceImpl;

/**
 * Unit tests for AccountingPeriodService
 * 
 * Tests monthly accounting period management including
 * period ID calculation and prior period detection.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountingPeriodService Unit Tests")
class AccountingPeriodServiceTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneId.systemDefault());

    @Spy
    private Clock clock = TEST_CLOCK;

    @InjectMocks
    private AccountingPeriodServiceImpl service;

    private Instant now;
    private String currentPeriod;

    @BeforeEach
    void setUp() {
        now = TEST_CLOCK.instant();
        currentPeriod = YearMonth.now(TEST_CLOCK).toString();
    }

    // ===== GET CURRENT PERIOD ID TESTS =====

    @Test
    @DisplayName("getCurrentPeriodId - returns current month in YYYY-MM format")
    void getCurrentPeriodId_returnsCurrentMonth() {
        // Act
        String result = service.getCurrentPeriodId();

        // Assert
        assertThat(result).isEqualTo(currentPeriod).matches("\\d{4}-\\d{2}");
    }

    // ===== GET PERIOD ID FOR DATE TESTS =====

    @Test
    @DisplayName("getPeriodIdForDate - returns correct period for given date")
    void getPeriodIdForDate_success() {
        // Arrange
        LocalDate testDate = LocalDate.of(2026, 3, 15);
        Instant testInstant = testDate.atStartOfDay(ZoneId.systemDefault()).toInstant();

        // Act
        String result = service.getPeriodIdForDate(testInstant);

        // Assert
        assertThat(result).isEqualTo("2026-03");
    }

    @Test
    @DisplayName("getPeriodIdForDate - returns correct period for first day of month")
    void getPeriodIdForDate_firstDayOfMonth() {
        // Arrange
        LocalDate testDate = LocalDate.of(2026, 1, 1);
        Instant testInstant = testDate.atStartOfDay(ZoneId.systemDefault()).toInstant();

        // Act
        String result = service.getPeriodIdForDate(testInstant);

        // Assert
        assertThat(result).isEqualTo("2026-01");
    }

    @Test
    @DisplayName("getPeriodIdForDate - returns correct period for last day of month")
    void getPeriodIdForDate_lastDayOfMonth() {
        // Arrange
        LocalDate testDate = LocalDate.of(2026, 2, 28);
        Instant testInstant = testDate.atStartOfDay(ZoneId.systemDefault()).toInstant();

        // Act
        String result = service.getPeriodIdForDate(testInstant);

        // Assert
        assertThat(result).isEqualTo("2026-02");
    }

    @Test
    @DisplayName("getPeriodIdForDate - returns correct period for current instant")
    void getPeriodIdForDate_currentInstant() {
        // Act
        String result = service.getPeriodIdForDate(now);

        // Assert
        assertThat(result).isEqualTo(currentPeriod);
    }

    // ===== IS PRIOR PERIOD TESTS =====

    @Test
    @DisplayName("isPriorPeriod - returns true for date in previous month")
    void isPriorPeriod_previousMonth_returnsTrue() {
        // Arrange
        Instant previousMonth = now.minus(35, ChronoUnit.DAYS);

        // Act
        boolean result = service.isPriorPeriod(previousMonth);

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isPriorPeriod - returns false for date in current month")
    void isPriorPeriod_currentMonth_returnsFalse() {
        // Act
        boolean result = service.isPriorPeriod(now);

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isPriorPeriod - returns false for date in future month")
    void isPriorPeriod_futureMonth_returnsFalse() {
        // Arrange
        Instant futureMonth = now.plus(35, ChronoUnit.DAYS);

        // Act
        boolean result = service.isPriorPeriod(futureMonth);

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isPriorPeriod - returns true for date one year ago")
    void isPriorPeriod_oneYearAgo_returnsTrue() {
        // Arrange
        Instant oneYearAgo = now.minus(365, ChronoUnit.DAYS);

        // Act
        boolean result = service.isPriorPeriod(oneYearAgo);

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isPriorPeriod - returns true for first day of previous month")
    void isPriorPeriod_firstDayPreviousMonth_returnsTrue() {
        // Arrange
        YearMonth previousMonth = YearMonth.now(TEST_CLOCK).minusMonths(1);
        LocalDate firstDay = previousMonth.atDay(1);
        Instant instant = firstDay.atStartOfDay(ZoneId.systemDefault()).toInstant();

        // Act
        boolean result = service.isPriorPeriod(instant);

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isPriorPeriod - returns false for first day of current month")
    void isPriorPeriod_firstDayCurrentMonth_returnsFalse() {
        // Arrange
        YearMonth currentMonth = YearMonth.now(TEST_CLOCK);
        LocalDate firstDay = currentMonth.atDay(1);
        Instant instant = firstDay.atStartOfDay(ZoneId.systemDefault()).toInstant();

        // Act
        boolean result = service.isPriorPeriod(instant);

        // Assert
        assertThat(result).isFalse();
    }

    // ===== IS PERIOD OPEN TESTS =====

    @Test
    @DisplayName("isPeriodOpen - returns true for any period (Phase 2.1)")
    void isPeriodOpen_alwaysReturnsTrue() {
        // Act
        boolean current = service.isPeriodOpen(currentPeriod);
        boolean past = service.isPeriodOpen("2025-01");
        boolean future = service.isPeriodOpen("2027-12");

        // Assert
        assertThat(current).isTrue();
        assertThat(past).isTrue();
        assertThat(future).isTrue();
    }

    @Test
    @DisplayName("isPeriodOpen - returns true for current period")
    void isPeriodOpen_currentPeriod_returnsTrue() {
        // Act
        boolean result = service.isPeriodOpen(currentPeriod);

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isPeriodOpen - returns true for any formatted period ID")
    void isPeriodOpen_anyPeriod_returnsTrue() {
        // Arrange
        String testPeriod = "2026-06";

        // Act
        boolean result = service.isPeriodOpen(testPeriod);

        // Assert
        assertThat(result).isTrue();
    }
}
