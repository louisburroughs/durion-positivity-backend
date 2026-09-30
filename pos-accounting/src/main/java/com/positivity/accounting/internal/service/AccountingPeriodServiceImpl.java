package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.dto.CloseReadinessResponse;
import com.positivity.accounting.internal.bankrec.readmodel.BankReconciliationCloseReadiness;
import com.positivity.accounting.internal.bankrec.readmodel.BankReconciliationCloseReadiness.CloseDecision;
import com.positivity.accounting.internal.dto.AccountingPeriodResponse;
import com.positivity.accounting.internal.dto.PeriodCloseRequest;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodNotFoundException;
import com.positivity.accounting.internal.exception.AccountingPeriodStateException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.PeriodBankReconciliationIncompleteException;
import com.positivity.accounting.internal.exception.PeriodCloseBlockedException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing accounting periods (AD-012).
 *
 * Table-backed implementation (story B1, issue #937) replacing the Phase 2.1
 * "all periods open" stub:
 * - Monthly periods (YYYY-MM), two-state lifecycle OPEN -> CLOSED (D-7)
 * - Missing period row counts as OPEN; rows are auto-provisioned on posting
 * - Close is blocked by DRAFT journal entries dated inside the period
 * - Reopen requires a mandatory justification
 * - Close/reopen write {@link AccountingAuditLog} rows with the acting user
 *
 * Full enforcement across all posting paths is story B2.
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan -
 *      Story B1</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingPeriodServiceImpl implements AccountingPeriodService {

    private static final String SYSTEM = "SYSTEM";
    private static final String AUDIT_ENTITY_TYPE = "ACCOUNTING_PERIOD";

    private final Clock clock;
    private final AccountingPeriodRepository periodRepository;
    private final TenantResolver tenantResolver;
    private final JournalEntryRepository journalEntryRepository;
    private final AccountingAuditLogRepository auditLogRepository;
    private final BankReconciliationCloseReadiness closeReadiness;

    @Override
    @NonNull
    public String getCurrentPeriodId() {
        YearMonth currentMonth = YearMonth.now(clock);
        String periodId = currentMonth.toString(); // Format: YYYY-MM
        log.debug("Current accounting period: {}", periodId);
        return periodId;
    }

    @Override
    @NonNull
    public String getPeriodIdForDate(@NonNull Instant date) {
        LocalDate localDate = date.atZone(ZoneId.systemDefault()).toLocalDate();
        YearMonth yearMonth = YearMonth.from(localDate);
        String periodId = yearMonth.toString(); // Format: YYYY-MM
        log.debug("Period for date {}: {}", date, periodId);
        return periodId;
    }

    @Override
    public boolean isPriorPeriod(@NonNull Instant date) {
        String datePeriodId = getPeriodIdForDate(date);
        String currentPeriodId = getCurrentPeriodId();
        boolean isPrior = datePeriodId.compareTo(currentPeriodId) < 0;
        log.debug(
                "Is {} prior period? {} (date period: {}, current: {})", date, isPrior, datePeriodId, currentPeriodId);
        return isPrior;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isPeriodOpen(@NonNull String periodId) {
        YearMonth yearMonth = parsePeriodCode(periodId);
        boolean open = periodRepository
                .findByPeriodCode(yearMonth.toString())
                .map(period -> period.getStatus() == AccountingPeriodStatus.OPEN)
                // Missing row counts as OPEN: auto-provisioning happens on posting, not on read.
                .orElse(true);
        log.debug("Period {} open: {}", periodId, open);
        return open;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isPeriodOpen(@NonNull LocalDate date) {
        return isPeriodOpen(YearMonth.from(date).toString());
    }

    @Override
    @NonNull
    @Transactional
    public AccountingPeriodResponse ensurePeriodExists(@NonNull LocalDate date) {
        return toResponse(findOrProvision(YearMonth.from(date)));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<AccountingPeriodResponse> listPeriods() {
        return periodRepository.findAllByOrderByPeriodCodeDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @NonNull
    @Transactional
    public AccountingPeriodResponse closePeriod(@NonNull String periodCode) {
        // Declared here, not only as the interface default, so a call through the proxy opens the transaction.
        return closePeriod(periodCode, null);
    }

    @Override
    @NonNull
    @Transactional
    public AccountingPeriodResponse closePeriod(@NonNull String periodCode, @Nullable PeriodCloseRequest request) {
        YearMonth yearMonth = parsePeriodCode(periodCode);
        String canonicalCode = yearMonth.toString();

        // Locked read (FOR UPDATE): the close serializes against an in-flight gated posting (AccountingPeriodGate)
        // and re-reads the live balances below under the lock (SPEC-manual-bank-reconciliation I3).
        AccountingPeriod period =
                periodRepository.findWithLockByPeriodCode(canonicalCode).orElseGet(() -> lockAfterProvision(yearMonth));

        if (period.getStatus() == AccountingPeriodStatus.CLOSED) {
            throw new AccountingPeriodStateException(
                    canonicalCode, AccountingPeriodStatus.CLOSED, "Period " + canonicalCode + " is already CLOSED");
        }

        List<UUID> draftEntryIds = findDraftEntryIdsInside(period);
        if (!draftEntryIds.isEmpty()) {
            log.info("Close of period {} blocked by {} DRAFT journal entries", canonicalCode, draftEntryIds.size());
            throw new PeriodCloseBlockedException(canonicalCode, draftEntryIds);
        }

        CloseReadinessResponse readiness = closeReadiness.evaluate(period, true);
        CloseDecision decision;
        try {
            decision =
                    closeReadiness.decide(readiness, request != null ? request.getBankReconciliationException() : null);
        } catch (PeriodBankReconciliationIncompleteException e) {
            log.info(
                    "Close of period {} refused by bank reconciliation policy {}: {}",
                    canonicalCode,
                    readiness.policy(),
                    BankReconciliationCloseReadiness.summary(readiness));
            throw e;
        }

        String actor = currentActor();
        String summary = BankReconciliationCloseReadiness.summary(readiness);
        if (decision.exceptionGranted()) {
            AccountingAuditLog exceptionRow = auditRow(period, "PERIOD_CLOSE_BANKREC_EXCEPTION", actor);
            exceptionRow.setJustification(decision.justification());
            exceptionRow.setOldValue(BankReconciliationCloseReadiness.snapshot(readiness));
            exceptionRow.setNewValue(AccountingPeriodStatus.CLOSED.name());
            auditLogRepository.save(exceptionRow);
            log.info("Period {} closes on a bank reconciliation exception by {}: {}", canonicalCode, actor, summary);
        }

        period.setStatus(AccountingPeriodStatus.CLOSED);
        period.setClosedAt(clock.instant());
        period.setClosedBy(actor);
        AccountingPeriod saved = periodRepository.save(period);

        AccountingAuditLog closeRow = auditRow(saved, "PERIOD_CLOSE", actor);
        closeRow.setOldValue(AccountingPeriodStatus.OPEN.name());
        closeRow.setNewValue(AccountingPeriodStatus.CLOSED.name() + ";" + summary);
        auditLogRepository.save(closeRow);
        log.info("Period {} closed by {}", canonicalCode, actor);
        AccountingPeriodResponse response = toResponse(saved);
        response.setBankReconciliationReady(decision.bankReconciliationReady());
        response.setBankReconciliationException(decision.exceptionGranted());
        return response;
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public CloseReadinessResponse getCloseReadiness(@NonNull String periodCode) {
        YearMonth yearMonth = parsePeriodCode(periodCode);
        AccountingPeriod period = periodRepository
                .findByPeriodCode(yearMonth.toString())
                .orElseGet(() -> {
                    AccountingPeriod transientPeriod = new AccountingPeriod();
                    transientPeriod.setPeriodCode(yearMonth.toString());
                    transientPeriod.setStartDate(yearMonth.atDay(1));
                    transientPeriod.setEndDate(yearMonth.atEndOfMonth());
                    transientPeriod.setStatus(AccountingPeriodStatus.OPEN);
                    return transientPeriod;
                });
        return closeReadiness.evaluate(period);
    }

    @Override
    @NonNull
    @Transactional
    public AccountingPeriodResponse reopenPeriod(@NonNull String periodCode, @NonNull String justification) {
        YearMonth yearMonth = parsePeriodCode(periodCode);
        String canonicalCode = yearMonth.toString();

        if (justification.isBlank()) {
            throw new InvalidRequestParameterException("A non-blank justification is required to reopen a period");
        }

        AccountingPeriod period = periodRepository
                .findByPeriodCode(canonicalCode)
                .orElseThrow(() -> new AccountingPeriodNotFoundException(
                        canonicalCode, "Period " + canonicalCode + " does not exist"));

        if (period.getStatus() == AccountingPeriodStatus.OPEN) {
            throw new AccountingPeriodStateException(
                    canonicalCode, AccountingPeriodStatus.OPEN, "Period " + canonicalCode + " is already OPEN");
        }

        String actor = currentActor();
        period.setStatus(AccountingPeriodStatus.OPEN);
        period.setReopenedAt(clock.instant());
        period.setReopenedBy(actor);
        period.setReopenJustification(justification);
        AccountingPeriod saved = periodRepository.save(period);

        writeAuditRow(
                saved,
                "PERIOD_REOPEN",
                actor,
                AccountingPeriodStatus.CLOSED,
                AccountingPeriodStatus.OPEN,
                justification);
        log.info("Period {} reopened by {} (justification recorded)", canonicalCode, actor);
        return toResponse(saved);
    }

    /**
     * Find the period row for the month, provisioning an OPEN row when absent.
     *
     * <p>Concurrency-safe on the caller's own connection and transaction: the insert is
     * {@code ON CONFLICT DO NOTHING}, so a collision with a concurrent auto-provisioner raises no
     * constraint violation, leaves this transaction committable (no rollback-only mark), and needs
     * no second ({@code REQUIRES_NEW}) connection, which would deadlock a small pool when several
     * first-use requests each hold one already (#2342). On PostgreSQL a concurrent insert of the
     * same period waits for the in-flight inserter to finish, then does nothing; the re-read then
     * returns whichever row won. {@code created_by}/{@code modified_by} carry the same actor the
     * entity's {@code @PrePersist} would have stamped.
     */
    private AccountingPeriod findOrProvision(YearMonth yearMonth) {
        String periodCode = yearMonth.toString();
        return periodRepository.findByPeriodCode(periodCode).orElseGet(() -> {
            int inserted = periodRepository.insertIfAbsent(
                    tenantResolver.require(),
                    UUIDv7Generator.generate(),
                    periodCode,
                    yearMonth.atDay(1),
                    yearMonth.atEndOfMonth(),
                    clock.instant(),
                    currentActor());
            if (inserted > 0) {
                log.info("Auto-provisioned OPEN accounting period {}", periodCode);
            } else {
                log.debug("Concurrent auto-provision of period {}; re-reading", periodCode);
            }
            return periodRepository
                    .findByPeriodCode(periodCode)
                    .orElseThrow(() -> new IllegalStateException(
                            "accounting_period row missing after auto-provision: " + periodCode));
        });
    }

    /**
     * Provision a period row so a close request for a valid YYYY-MM month that
     * has already started can proceed (auto-provision-then-close semantics).
     * A month that has not started yet cannot be closed.
     */
    private AccountingPeriod provisionForClose(YearMonth yearMonth) {
        LocalDate today = LocalDate.now(clock);
        if (yearMonth.atDay(1).isAfter(today)) {
            throw new AccountingPeriodNotFoundException(
                    yearMonth.toString(), "Period " + yearMonth + " does not exist and its month has not started");
        }
        return findOrProvision(yearMonth);
    }

    /** Provisions the period for a close and re-reads it under the row lock. */
    private AccountingPeriod lockAfterProvision(YearMonth yearMonth) {
        AccountingPeriod provisioned = provisionForClose(yearMonth);
        return periodRepository
                .findWithLockByPeriodCode(provisioned.getPeriodCode())
                .orElse(provisioned);
    }

    private List<UUID> findDraftEntryIdsInside(AccountingPeriod period) {
        return journalEntryRepository
                .findByStatusAndTransactionDateInRange(
                        JournalEntryStatus.DRAFT,
                        period.getStartDate().atStartOfDay(),
                        period.getEndDate().plusDays(1).atStartOfDay())
                .stream()
                .map(JournalEntry::getJournalEntryId)
                .toList();
    }

    private void writeAuditRow(
            AccountingPeriod period,
            String operation,
            String actor,
            AccountingPeriodStatus oldStatus,
            AccountingPeriodStatus newStatus,
            String justification) {
        AccountingAuditLog auditLog = auditRow(period, operation, actor);
        auditLog.setJustification(justification);
        auditLog.setOldValue(oldStatus.name());
        auditLog.setNewValue(newStatus.name());
        auditLogRepository.save(auditLog);
    }

    private static AccountingAuditLog auditRow(AccountingPeriod period, String operation, String actor) {
        AccountingAuditLog auditLog = new AccountingAuditLog();
        auditLog.setEntityType(AUDIT_ENTITY_TYPE);
        auditLog.setEntityId(period.getPeriodId());
        auditLog.setOperation(operation);
        auditLog.setUserId(actor);
        return auditLog;
    }

    private static String currentActor() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }

    private static YearMonth parsePeriodCode(String periodCode) {
        try {
            return YearMonth.parse(periodCode);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestParameterException(
                    "Invalid period code '" + periodCode + "': expected format YYYY-MM", e);
        }
    }

    private AccountingPeriodResponse toResponse(AccountingPeriod period) {
        return AccountingPeriodResponse.builder()
                .periodId(period.getPeriodId())
                .periodCode(period.getPeriodCode())
                .startDate(period.getStartDate())
                .endDate(period.getEndDate())
                .status(period.getStatus())
                .closedAt(period.getClosedAt())
                .closedBy(period.getClosedBy())
                .reopenedAt(period.getReopenedAt())
                .reopenedBy(period.getReopenedBy())
                .reopenJustification(period.getReopenJustification())
                .build();
    }
}
