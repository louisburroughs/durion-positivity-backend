package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.dto.CloseReadinessResponse;
import com.positivity.accounting.internal.dto.AccountingPeriodResponse;
import com.positivity.accounting.internal.dto.PeriodCloseRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
     * Get the current accounting period ID: this month in the tenant's accounting-calendar zone (#2558).
     *
     * @return Current period ID in format YYYY-MM (e.g., "2026-02")
     * @throws com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException when the tenant has no
     *     accounting time zone
     */
    @NonNull
    String getCurrentPeriodId();

    /**
     * Get the accounting period ID for a given instant, dated in the tenant's accounting-calendar zone (#2558).
     *
     * @param date Date to find period for
     * @return Period ID in format YYYY-MM
     * @throws com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException when the tenant has no
     *     accounting time zone
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
     * posting flows). Concurrency-safe: the provisioning insert runs in its
     * own transaction, so when two callers race on the unique period_code
     * constraint only that inner insert transaction rolls back — the loser's
     * own transaction survives and returns the winner's row via re-read.
     * Never changes the status of an existing row.
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
     * Close a period (OPEN -> CLOSED) with no close body.
     *
     * @see #closePeriod(String, PeriodCloseRequest)
     */
    @NonNull
    default AccountingPeriodResponse closePeriod(@NonNull String periodCode) {
        return closePeriod(periodCode, null);
    }

    /**
     * Close a period (OPEN -> CLOSED).
     *
     * A valid YYYY-MM period with no row whose month has already started is
     * auto-provisioned and then closed. Closing fails when the period is
     * already CLOSED, or when DRAFT journal entries are dated inside the
     * period (the blocking entry IDs are reported). Then bank reconciliation
     * readiness is evaluated under the period row lock (SPEC-manual-bank-reconciliation
     * §5.9; story S6, #2305) and the tenant's close policy decides: under
     * {@code REQUIRED*} BLOCKING checks refuse the close unless a valid
     * exception is granted ({@code REQUIRED_WITH_EXCEPTION} only). The
     * {@code PERIOD_CLOSE} audit row carries the readiness summary; a granted
     * exception adds a {@code PERIOD_CLOSE_BANKREC_EXCEPTION} row.
     *
     * @param periodCode Period code (YYYY-MM) to close
     * @param request optional body carrying a bank reconciliation exception
     * @return the closed period with {@code bankReconciliationReady} and
     *         {@code bankReconciliationException}
     * @throws IllegalArgumentException if periodCode is not a valid YYYY-MM code
     * @throws com.positivity.accounting.internal.exception.AccountingPeriodNotFoundException
     *         if the period does not exist and its month has not started
     * @throws com.positivity.accounting.internal.exception.AccountingPeriodStateException
     *         if the period is already CLOSED
     * @throws com.positivity.accounting.internal.exception.PeriodCloseBlockedException
     *         if DRAFT journal entries are dated inside the period, or (its
     *         subtype {@code PeriodBankReconciliationIncompleteException})
     *         when the bank reconciliation policy refuses the close
     * @throws com.positivity.accounting.internal.exception.PeriodCloseExceptionNotPermittedException
     *         if an exception is asked for without both close and override authority
     */
    @NonNull
    AccountingPeriodResponse closePeriod(@NonNull String periodCode, @Nullable PeriodCloseRequest request);

    /**
     * Bank reconciliation close readiness of a period (SPEC-manual-bank-reconciliation §5.3; story S6, #2305):
     * derived, never persisted; a month with no period row is evaluated as OPEN without provisioning it.
     *
     * @param periodCode Period code (YYYY-MM)
     * @throws IllegalArgumentException if periodCode is not a valid YYYY-MM code
     */
    @NonNull
    CloseReadinessResponse getCloseReadiness(@NonNull String periodCode);

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
