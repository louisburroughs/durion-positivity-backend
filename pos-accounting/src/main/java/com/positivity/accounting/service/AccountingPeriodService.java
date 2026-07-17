package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.AccountingPeriodResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Accounting period lifecycle service (AD-012: posting only in open periods).
 *
 * Monthly cadence, two-state lifecycle OPEN -> CLOSED per decision D-7
 * (plan-odoo-parity-pos-accounting). Periods are auto-provisioned OPEN on
 * first posting into a nonexistent period; a missing period row counts as
 * OPEN on reads.
 */
public interface AccountingPeriodService {

    /**
     * Get the current accounting period ID.
     *
     * @return Current period ID in format YYYY-MM (e.g., "2026-02")
     */
    @NonNull
    String getCurrentPeriodId();

    /**
     * Get the accounting period ID for a given date.
     *
     * @param date Date to find period for
     * @return Period ID in format YYYY-MM
     */
    @NonNull
    String getPeriodIdForDate(@NonNull Instant date);

    /**
     * Check if a given date falls in a prior period (before current month).
     *
     * @param date Date to check
     * @return true if date is in a prior period, false if current or future
     */
    boolean isPriorPeriod(@NonNull Instant date);

    /**
     * Check if a period is open for posting.
     *
     * Reads the accounting_period table; a period with no row counts as OPEN
     * (auto-provisioning happens on posting, not on read).
     *
     * @param periodId Period ID (YYYY-MM) to check
     * @return true if the period is OPEN or has no row, false if CLOSED
     * @throws IllegalArgumentException if periodId is not a valid YYYY-MM code
     */
    boolean isPeriodOpen(@NonNull String periodId);

    /**
     * Check if the period containing the given date is open for posting.
     *
     * @param date Date whose month period is checked
     * @return true if the period is OPEN or has no row, false if CLOSED
     */
    boolean isPeriodOpen(@NonNull LocalDate date);

    /**
     * Ensure a period row exists for the month containing the given date,
     * auto-provisioning an OPEN row if absent (zero-admin behavior for
     * posting flows; concurrency-safe via unique period_code plus
     * duplicate-key re-read). Never changes the status of an existing row.
     *
     * @param date Date whose month period must exist
     * @return the existing or newly provisioned period
     */
    @NonNull
    AccountingPeriodResponse ensurePeriodExists(@NonNull LocalDate date);

    /**
     * List all known periods, most recent first (descending period code).
     *
     * @return all persisted periods; months never posted into may be absent
     */
    @NonNull
    List<AccountingPeriodResponse> listPeriods();

    /**
     * Close a period (OPEN -> CLOSED).
     *
     * A valid YYYY-MM period with no row whose month has already started is
     * auto-provisioned and then closed. Closing fails when the period is
     * already CLOSED, or when DRAFT journal entries are dated inside the
     * period (the blocking entry IDs are reported).
     *
     * @param periodCode Period code (YYYY-MM) to close
     * @return the closed period
     * @throws IllegalArgumentException if periodCode is not a valid YYYY-MM code
     * @throws com.positivity.accounting.internal.exception.AccountingPeriodNotFoundException
     *         if the period does not exist and its month has not started
     * @throws com.positivity.accounting.internal.exception.AccountingPeriodStateException
     *         if the period is already CLOSED
     * @throws com.positivity.accounting.internal.exception.PeriodCloseBlockedException
     *         if DRAFT journal entries are dated inside the period
     */
    @NonNull
    AccountingPeriodResponse closePeriod(@NonNull String periodCode);

    /**
     * Reopen a CLOSED period (CLOSED -> OPEN) with a mandatory justification.
     *
     * @param periodCode Period code (YYYY-MM) to reopen
     * @param justification Mandatory non-blank reason, recorded on the period
     *        and in the audit trail
     * @return the reopened period
     * @throws IllegalArgumentException if periodCode is invalid or
     *         justification is blank
     * @throws com.positivity.accounting.internal.exception.AccountingPeriodNotFoundException
     *         if no period row exists for the code
     * @throws com.positivity.accounting.internal.exception.AccountingPeriodStateException
     *         if the period is already OPEN
     */
    @NonNull
    AccountingPeriodResponse reopenPeriod(@NonNull String periodCode, @NonNull String justification);
}
