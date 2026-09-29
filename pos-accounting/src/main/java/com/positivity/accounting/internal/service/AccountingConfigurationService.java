package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.BankReconciliationPolicyRequest;
import com.positivity.accounting.internal.dto.BankReconciliationPolicyResponse;
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

    /**
     * The tenant's effective bank reconciliation policy (SPEC-manual-bank-reconciliation §5.2; story S6, #2305):
     * the defaults stand in for a setting with no row; {@code updatedAt} / {@code updatedBy} are those of the
     * latest {@code BANK_REC_POLICY_SET} change.
     */
    @NonNull
    BankReconciliationPolicyResponse getBankReconciliationPolicy();

    /**
     * Replace the five bank reconciliation settings (§5.2): each setting whose effective value changes is written
     * (a {@code null} threshold deletes its row) and audited as one {@code BANK_REC_POLICY_SET} row with the old and
     * new value and the justification; an unchanged setting writes nothing.
     *
     * @return the stored policy
     * @throws com.positivity.accounting.internal.bankrec.intake.BankRecException 400 {@code VALIDATION_ERROR} for a
     *     missing field or a threshold finer than the functional currency's minor unit, 400 {@code
     *     JUSTIFICATION_REQUIRED} for a justification of 1–9 characters
     */
    @NonNull
    BankReconciliationPolicyResponse setBankReconciliationPolicy(@NonNull BankReconciliationPolicyRequest request);
}
