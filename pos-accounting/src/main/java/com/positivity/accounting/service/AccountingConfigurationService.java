package com.positivity.accounting.service;

import java.time.LocalDate;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * Org-level accounting configuration service (story B2, issue #944).
 *
 * <p>Currently owns a single setting: the <b>hard-lock date</b> — no journal
 * entry may ever be posted with a transaction date strictly before it, with
 * no override path (the irreversible piece of Odoo's lock-date model). The
 * date is monotonic: it can only move forward in time, never backward.
 */
public interface AccountingConfigurationService {

    /**
     * The org-level hard-lock date, when configured.
     *
     * @return the hard-lock date, or empty when no hard lock has been set
     */
    Optional<LocalDate> getHardLockDate();

    /**
     * Set the org-level hard-lock date with a mandatory justification.
     *
     * <p>Monotonic-forward-only: the new date must be on or after the
     * currently stored hard-lock date. Every change is audit-logged with the
     * acting user (ADR-0018).
     *
     * @param hardLockDate  new hard-lock date; postings dated strictly before
     *                      it are permanently rejected
     * @param justification mandatory non-blank reason, recorded in the audit
     *                      trail
     * @return the stored hard-lock date
     * @throws IllegalArgumentException if justification is blank
     * @throws com.positivity.accounting.internal.exception.HardLockDateRegressionException
     *         if the new date is before the currently stored hard-lock date
     *         (422: HARD_LOCK_DATE_REGRESSION)
     */
    @NonNull
    LocalDate setHardLockDate(@NonNull LocalDate hardLockDate, @NonNull String justification);
}
