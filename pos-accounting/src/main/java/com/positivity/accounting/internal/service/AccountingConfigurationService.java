package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.AgedThresholdsRequest;
import com.positivity.accounting.internal.dto.AgedThresholdsResponse;
import com.positivity.accounting.internal.dto.BankReconciliationPolicyRequest;
import com.positivity.accounting.internal.dto.BankReconciliationPolicyResponse;
import com.positivity.accounting.internal.dto.CashSafetyCushionRequest;
import com.positivity.accounting.internal.dto.CashSafetyCushionResponse;
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
     * Set the tenant's accounting-calendar zone ({@code ACCOUNTING_TIME_ZONE}, #2558): the zone in which every
     * instant becomes a posting date and a period. Audited with the old and new zone and the acting user; the
     * resolver's cached zone is evicted. Posted entries keep their dates: a change never re-cuts history.
     *
     * @param timeZone an IANA region id, such as {@code America/Chicago}
     * @return the stored zone id; the same id unchanged when it was already the zone (nothing written)
     * @throws com.positivity.accounting.internal.exception.InvalidAccountingTimeZoneException 400 when the id is
     *     unknown, a fixed offset or a {@code SystemV/*} id
     * @throws com.positivity.accounting.internal.exception.AccountingTimeZoneLockedException 409 once the tenant has
     *     closed a period or set a hard-lock date
     */
    @NonNull
    String setAccountingTimeZone(@NonNull String timeZone);

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

    /** The tenant's cash safety cushion in its functional currency; the amount is null while it is unset (#2515). */
    @NonNull
    CashSafetyCushionResponse getCashSafetyCushion();

    /**
     * Set or clear the cash safety cushion ({@code CASH_SAFETY_CUSHION}, #2515): a management threshold of the cash
     * outlook, never posted and never subtracted from cash. A {@code null} amount clears it (the row is deleted). A
     * change is audited old to new with the justification and the request id; a replay of a request id writes nothing.
     *
     * @return the stored cushion
     * @throws com.positivity.accounting.internal.exception.InvalidRequestParameterException 400
     *     {@code VALIDATION_ERROR} for a missing field, an unknown currency code, a negative amount, or one finer
     *     than the functional currency's minor unit
     * @throws com.positivity.accounting.internal.bankrec.intake.BankRecException 400 {@code JUSTIFICATION_REQUIRED}
     *     for a justification of 1-9 characters
     * @throws com.positivity.accounting.internal.exception.CurrencyNotSupportedException 422 {@code
     *     CURRENCY_NOT_SUPPORTED} for a code other than the functional currency
     * @throws com.positivity.accounting.internal.exception.IdempotencyConflictException 409 for a request id already
     *     used with another body
     */
    @NonNull
    CashSafetyCushionResponse setCashSafetyCushion(@NonNull CashSafetyCushionRequest request);

    /** The tenant's effective aged-item thresholds; a default stands in for a setting with no row (#2515). */
    @NonNull
    AgedThresholdsResponse getAgedThresholds();

    /**
     * Replace the three aged-item thresholds (#2515): each whose effective value changes is written and audited as
     * one {@code AGED_THRESHOLDS_SET} row with the old and new value, the justification and the request id; an
     * unchanged one writes nothing. A replay of a request id writes nothing.
     *
     * @return the stored thresholds
     * @throws com.positivity.accounting.internal.exception.InvalidRequestParameterException 400
     *     {@code VALIDATION_ERROR} for a missing field or a value outside its range
     * @throws com.positivity.accounting.internal.exception.IdempotencyConflictException 409 for a request id already
     *     used with another body
     */
    @NonNull
    AgedThresholdsResponse setAgedThresholds(@NonNull AgedThresholdsRequest request);
}
