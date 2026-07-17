package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.AccountingPeriodResponse;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodNotFoundException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.service.AccountingPeriodServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for AccountingPeriodService.
 *
 * Tests monthly accounting period management including period ID calculation,
 * prior period detection, table-backed open checks (missing row counts as
 * OPEN), auto-provision duplicate-key race handling, and lifecycle input
 * validation. Persistence-backed lifecycle behavior is covered by
 * {@code AccountingPeriodLifecycleTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountingPeriodService Unit Tests")
class AccountingPeriodServiceTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-15T00:00:00Z"), ZoneId.systemDefault());

    @Spy
    private Clock clock = TEST_CLOCK;

    @Mock
    private AccountingPeriodRepository periodRepository;

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private AccountingAuditLogRepository auditLogRepository;

    @InjectMocks
    private AccountingPeriodServiceImpl service;

    private Instant now;
    private String currentPeriod;

    @BeforeEach
    void setUp() {
        now = TEST_CLOCK.instant();
        currentPeriod = YearMonth.now(TEST_CLOCK).toString();
    }

    private static AccountingPeriod period(String code, AccountingPeriodStatus status) {
        YearMonth yearMonth = YearMonth.parse(code);
        AccountingPeriod period = new AccountingPeriod();
        period.setPeriodId(UUID.randomUUID());
        period.setPeriodCode(code);
        period.setStartDate(yearMonth.atDay(1));
        period.setEndDate(yearMonth.atEndOfMonth());
        period.setStatus(status);
        return period;
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
    @DisplayName("isPeriodOpen - missing period row counts as OPEN")
    void isPeriodOpen_missingRow_returnsTrue() {
        // Arrange
        when(periodRepository.findByPeriodCode("2025-01")).thenReturn(Optional.empty());

        // Act
        boolean result = service.isPeriodOpen("2025-01");

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isPeriodOpen - returns true for an OPEN period row")
    void isPeriodOpen_openRow_returnsTrue() {
        // Arrange
        when(periodRepository.findByPeriodCode(currentPeriod))
                .thenReturn(Optional.of(period(currentPeriod, AccountingPeriodStatus.OPEN)));

        // Act
        boolean result = service.isPeriodOpen(currentPeriod);

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isPeriodOpen - returns false for a CLOSED period row")
    void isPeriodOpen_closedRow_returnsFalse() {
        // Arrange
        when(periodRepository.findByPeriodCode("2023-12"))
                .thenReturn(Optional.of(period("2023-12", AccountingPeriodStatus.CLOSED)));

        // Act
        boolean result = service.isPeriodOpen("2023-12");

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isPeriodOpen(LocalDate) - resolves the month period of the date")
    void isPeriodOpen_byDate_resolvesMonthPeriod() {
        // Arrange
        when(periodRepository.findByPeriodCode("2023-12"))
                .thenReturn(Optional.of(period("2023-12", AccountingPeriodStatus.CLOSED)));

        // Act
        boolean result = service.isPeriodOpen(LocalDate.of(2023, 12, 15));

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isPeriodOpen - rejects malformed period code")
    void isPeriodOpen_invalidCode_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.isPeriodOpen("December-2023"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM");
    }

    // ===== AUTO-PROVISION RACE TESTS =====

    @Test
    @DisplayName("ensurePeriodExists - duplicate-key race falls back to re-read")
    void ensurePeriodExists_duplicateKeyRace_reReads() {
        // Arrange: first read misses, insert collides with a concurrent writer,
        // re-read finds the winner's row.
        AccountingPeriod winner = period("2024-01", AccountingPeriodStatus.OPEN);
        when(periodRepository.findByPeriodCode("2024-01"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(periodRepository.saveAndFlush(any(AccountingPeriod.class)))
                .thenThrow(new DataIntegrityViolationException("uq_accounting_period_code"));

        // Act
        AccountingPeriodResponse result = service.ensurePeriodExists(LocalDate.of(2024, 1, 15));

        // Assert
        assertThat(result.getPeriodId()).isEqualTo(winner.getPeriodId());
        assertThat(result.getPeriodCode()).isEqualTo("2024-01");
        assertThat(result.getStatus()).isEqualTo(AccountingPeriodStatus.OPEN);
    }

    @Test
    @DisplayName("ensurePeriodExists - existing row is returned without insert")
    void ensurePeriodExists_existingRow_noInsert() {
        // Arrange
        AccountingPeriod existing = period("2024-01", AccountingPeriodStatus.CLOSED);
        when(periodRepository.findByPeriodCode("2024-01")).thenReturn(Optional.of(existing));

        // Act
        AccountingPeriodResponse result = service.ensurePeriodExists(LocalDate.of(2024, 1, 2));

        // Assert: status of an existing row is never changed by provisioning
        assertThat(result.getStatus()).isEqualTo(AccountingPeriodStatus.CLOSED);
        verify(periodRepository, never()).saveAndFlush(any());
    }

    // ===== LIFECYCLE INPUT VALIDATION TESTS =====

    @Test
    @DisplayName("closePeriod - future month with no row cannot be closed (404)")
    void closePeriod_futureMonth_notFound() {
        // Arrange: clock is fixed at 2024-01-15
        when(periodRepository.findByPeriodCode("2024-02")).thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> service.closePeriod("2024-02"))
                .isInstanceOf(AccountingPeriodNotFoundException.class)
                .hasMessageContaining("2024-02");
        verify(periodRepository, never()).saveAndFlush(any());
        verify(periodRepository, never()).save(any());
    }

    @Test
    @DisplayName("closePeriod - rejects malformed period code")
    void closePeriod_invalidCode_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.closePeriod("2024/01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM");
    }

    @Test
    @DisplayName("reopenPeriod - blank justification is rejected before any lookup")
    void reopenPeriod_blankJustification_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.reopenPeriod("2024-01", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("justification");
        verify(periodRepository, never()).save(any());
    }
}
