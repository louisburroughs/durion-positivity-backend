package com.positivity.accounting.service;

import java.time.Instant;

public interface AccountingPeriodService {

    /**
     * Get the current open accounting period ID.
     *
     * @return Current period ID in format YYYY-MM (e.g., "2026-02")
     */
    String getCurrentPeriodId();

    /**
     * Get the accounting period ID for a given date.
     *
     * @param date Date to find period for
     * @return Period ID in format YYYY-MM
     */
    String getPeriodIdForDate(Instant date);

    /**
     * Check if a given date falls in a prior period (before current month).
     *
     * @param date Date to check
     * @return true if date is in a prior period, false if current or future
     */
    boolean isPriorPeriod(Instant date);

    /**
     * Check if a period is open for posting.
     *
     * Phase 2.1: All periods are open (no period close workflow yet).
     *
     * @param periodId Period ID to check
     * @return true (all periods open in Phase 2.1)
     */
    boolean isPeriodOpen(String periodId);
}
