package com.positivity.accounting.internal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import com.positivity.accounting.service.AccountingPeriodService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;

/**
 * Service for managing accounting periods.
 * 
 * Simplified implementation for Phase 2.1:
 * - Assumes monthly periods (YYYY-MM)
 * - Current period is the current month
 * - Prior period check: invoice date is before current month
 * 
 * Future enhancements:
 * - Support custom period definitions (quarterly, fiscal years)
 * - Period opening/closing workflow
 * - Period status management (OPEN, CLOSED, LOCKED)
 * - Hard-close enforcement
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/131">Issue
 *      #131</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingPeriodServiceImpl implements AccountingPeriodService {

    private final Clock clock;

    /**
     * Get the current open accounting period ID.
     * 
     * @return Current period ID in format YYYY-MM (e.g., "2026-02")
     */
    @Override
    public String getCurrentPeriodId() {
        YearMonth currentMonth = YearMonth.now(clock);
        String periodId = currentMonth.toString(); // Format: YYYY-MM
        log.debug("Current accounting period: {}", periodId);
        return periodId;
    }

    /**
     * Get the accounting period ID for a given date.
     * 
     * @param date Date to find period for
     * @return Period ID in format YYYY-MM
     */
    @Override
    public String getPeriodIdForDate(@NonNull Instant date) {
        LocalDate localDate = date.atZone(ZoneId.systemDefault()).toLocalDate();
        YearMonth yearMonth = YearMonth.from(localDate);
        String periodId = yearMonth.toString(); // Format: YYYY-MM
        log.debug("Period for date {}: {}", date, periodId);
        return periodId;
    }

    /**
     * Check if a given date falls in a prior period (before current month).
     * 
     * @param date Date to check
     * @return true if date is in a prior period, false if current or future
     */
    @Override
    public boolean isPriorPeriod(@NonNull Instant date) {
        String datePeriodId = getPeriodIdForDate(date);
        String currentPeriodId = getCurrentPeriodId();
        boolean isPrior = datePeriodId.compareTo(currentPeriodId) < 0;
        log.debug("Is {} prior period? {} (date period: {}, current: {})",
                date, isPrior, datePeriodId, currentPeriodId);
        return isPrior;
    }

    /**
     * Check if a period is open for posting.
     * 
     * Phase 2.1: All periods are open (no period close workflow yet).
     * 
     * @param periodId Period ID to check
     * @return true (all periods open in Phase 2.1)
     */
    @Override
    public boolean isPeriodOpen(@NonNull String periodId) {
        log.debug("Checking if period {} is open: true (Phase 2.1 - all periods open)", periodId);
        return true; // Simplified for Phase 2.1
    }
}
