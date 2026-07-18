package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.service.AccountingConfigurationService;
import com.positivity.accounting.service.AccountingPeriodService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single period-enforcement gate for all posting paths (story B2,
 * issue #944, decision D-7).
 *
 * <p>Every journal-entry posting funnels through
 * {@code JournalEntryServiceImpl.postJournalEntry} or
 * {@code reverseJournalEntry}, and both call this gate with the entry's
 * resolved transaction date. Rules, in order:
 *
 * <ol>
 * <li><b>Hard lock</b> — a transaction date strictly before the org-level
 * hard-lock date is rejected unconditionally: no override, distinct 422 code
 * {@code PERIOD_HARD_LOCKED}.</li>
 * <li><b>Open period</b> — posting proceeds. A missing period row counts as
 * OPEN (auto-provisioning is the caller's job via
 * {@link AccountingPeriodService#ensurePeriodExists}; the gate never blocks
 * on a missing row).</li>
 * <li><b>Closed period</b> — rejected with 422 {@code PERIOD_CLOSED}, unless
 * the caller holds {@code accounting:period:override} <em>and</em> supplies a
 * non-blank justification, in which case the posting is allowed and an
 * {@link AccountingAuditLog} row (operation {@code PERIOD_OVERRIDE_POST})
 * records period, entry, actor, and justification — the Durion-shaped
 * version of Odoo's lock-date exceptions.</li>
 * </ol>
 *
 * <p>The audit row joins the caller's posting transaction, so a posting that
 * subsequently fails rolls its override audit row back with it.
 *
 * <p><b>Concurrency vs. {@code closePeriod}:</b> the closed/hard-lock
 * evaluation in {@link #assertPostingAllowed} reads the period row with a
 * pessimistic lock ({@code SELECT ... FOR UPDATE}), so a concurrent
 * {@code closePeriod} — which updates that same row — serializes against the
 * in-flight gated posting instead of closing the period between the gate
 * check and the posting's commit. The lock is held to the end of the posting
 * transaction (hence {@code Propagation.MANDATORY}); the read-only
 * {@code isPeriodOpen} API and the engine's advisory
 * {@link #isPostingBlocked} pre-check stay unlocked. A missing period row
 * still counts as OPEN — there is nothing to lock, and provisioning races are
 * covered by the provisioner's REQUIRES_NEW insert plus the unique
 * constraint on the period code.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountingPeriodGate {

    /** Permission that allows posting into a CLOSED (not hard-locked) period. */
    public static final String OVERRIDE_AUTHORITY = "accounting:period:override";

    static final String AUDIT_OPERATION_PERIOD_OVERRIDE_POST = "PERIOD_OVERRIDE_POST";

    private static final String SYSTEM = "SYSTEM";
    private static final String AUDIT_ENTITY_TYPE_JOURNAL_ENTRY = "JOURNAL_ENTRY";

    private final AccountingPeriodService accountingPeriodService;
    private final AccountingPeriodRepository periodRepository;
    private final AccountingConfigurationService configurationService;
    private final AccountingAuditLogRepository auditLogRepository;

    /**
     * Assert that a journal entry dated {@code transactionDate} may be
     * posted, applying the hard-lock / closed-period / override rules above.
     *
     * @param transactionDate       the entry's (resolved) transaction date
     * @param journalEntryId        the entry being posted; recorded in the
     *                              override audit row
     * @param overrideJustification optional justification for posting into a
     *                              CLOSED period; only honored when the
     *                              caller holds {@link #OVERRIDE_AUTHORITY}
     * @throws AccountingPeriodHardLockedException if the date is strictly
     *         before the hard-lock date (422: PERIOD_HARD_LOCKED, no
     *         override)
     * @throws AccountingPeriodClosedException if the date's period is CLOSED
     *         and no valid override applies (422: PERIOD_CLOSED)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertPostingAllowed(
            @NonNull LocalDate transactionDate, @NonNull UUID journalEntryId, @Nullable String overrideJustification) {
        assertNotHardLocked(transactionDate);

        String periodCode = YearMonth.from(transactionDate).toString();

        // Locked read (FOR UPDATE): holding the period row until the posting
        // transaction ends closes the gate-vs-close window — a concurrent
        // closePeriod updates this row and must wait for the in-flight
        // posting (or, having committed first, is seen here as CLOSED). A
        // missing row counts as OPEN; there is nothing to lock.
        boolean periodOpen = periodRepository
                .findWithLockByPeriodCode(periodCode)
                .map(period -> period.getStatus() == AccountingPeriodStatus.OPEN)
                .orElse(true);
        if (periodOpen) {
            return;
        }

        if (overrideJustification == null || overrideJustification.isBlank()) {
            throw new AccountingPeriodClosedException(
                    periodCode,
                    "Transaction date " + transactionDate + " falls in CLOSED accounting period " + periodCode
                            + "; supply an override justification with the " + OVERRIDE_AUTHORITY
                            + " permission to post anyway");
        }
        if (!hasOverrideAuthority()) {
            throw new AccountingPeriodClosedException(
                    periodCode,
                    "Transaction date " + transactionDate + " falls in CLOSED accounting period " + periodCode
                            + "; caller lacks the " + OVERRIDE_AUTHORITY + " permission required to override");
        }

        recordOverrideAudit(periodCode, journalEntryId, overrideJustification);
    }

    /**
     * Non-throwing check for the posting-engine pre-check (autoPost path):
     * whether a posting dated {@code transactionDate} would be blocked by the
     * hard lock or a CLOSED period. The engine has no interactive caller, so
     * there is no override here — its remedy is reopen-then-reprocess.
     *
     * @param transactionDate the event's transaction date
     * @return true when posting would be rejected (hard-locked or CLOSED)
     */
    public boolean isPostingBlocked(@NonNull LocalDate transactionDate) {
        Optional<LocalDate> hardLockDate = configurationService.getHardLockDate();
        if (hardLockDate.isPresent() && transactionDate.isBefore(hardLockDate.get())) {
            return true;
        }
        return !accountingPeriodService.isPeriodOpen(transactionDate);
    }

    private void assertNotHardLocked(LocalDate transactionDate) {
        Optional<LocalDate> hardLockDate = configurationService.getHardLockDate();
        if (hardLockDate.isPresent() && transactionDate.isBefore(hardLockDate.get())) {
            throw new AccountingPeriodHardLockedException(
                    hardLockDate.get(),
                    "Transaction date " + transactionDate + " is before the hard-lock date " + hardLockDate.get()
                            + "; postings before the hard lock are permanently rejected and cannot be overridden");
        }
    }

    private void recordOverrideAudit(String periodCode, UUID journalEntryId, String justification) {
        String actor = currentActor();
        AccountingAuditLog auditLog = new AccountingAuditLog();
        auditLog.setEntityType(AUDIT_ENTITY_TYPE_JOURNAL_ENTRY);
        auditLog.setEntityId(journalEntryId);
        auditLog.setOperation(AUDIT_OPERATION_PERIOD_OVERRIDE_POST);
        auditLog.setUserId(actor);
        auditLog.setJustification(justification);
        auditLog.setNewValue("Posted into CLOSED period " + periodCode);
        auditLogRepository.save(auditLog);
        log.info(
                "Period override: journal entry {} posted into CLOSED period {} by {} (justification recorded)",
                journalEntryId,
                periodCode,
                actor);
    }

    private static boolean hasOverrideAuthority() {
        return SecurityContextHelper.isAuthenticated() && SecurityContextHelper.hasAuthority(OVERRIDE_AUTHORITY);
    }

    private static String currentActor() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }
}
