package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.AccountingPeriodResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodNotFoundException;
import com.positivity.accounting.internal.exception.AccountingPeriodStateException;
import com.positivity.accounting.internal.exception.PeriodCloseBlockedException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.service.AccountingPeriodService;
import com.positivity.security.common.SecurityContextHelper;
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
import org.springframework.dao.DataIntegrityViolationException;
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
    private final JournalEntryRepository journalEntryRepository;
    private final AccountingAuditLogRepository auditLogRepository;

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
        YearMonth yearMonth = parsePeriodCode(periodCode);
        String canonicalCode = yearMonth.toString();

        AccountingPeriod period =
                periodRepository.findByPeriodCode(canonicalCode).orElseGet(() -> provisionForClose(yearMonth));

        if (period.getStatus() == AccountingPeriodStatus.CLOSED) {
            throw new AccountingPeriodStateException(
                    canonicalCode, AccountingPeriodStatus.CLOSED, "Period " + canonicalCode + " is already CLOSED");
        }

        List<UUID> draftEntryIds = findDraftEntryIdsInside(period);
        if (!draftEntryIds.isEmpty()) {
            log.info("Close of period {} blocked by {} DRAFT journal entries", canonicalCode, draftEntryIds.size());
            throw new PeriodCloseBlockedException(canonicalCode, draftEntryIds);
        }

        String actor = currentActor();
        period.setStatus(AccountingPeriodStatus.CLOSED);
        period.setClosedAt(clock.instant());
        period.setClosedBy(actor);
        AccountingPeriod saved = periodRepository.save(period);

        writeAuditRow(saved, "PERIOD_CLOSE", actor, AccountingPeriodStatus.OPEN, AccountingPeriodStatus.CLOSED, null);
        log.info("Period {} closed by {}", canonicalCode, actor);
        return toResponse(saved);
    }

    @Override
    @NonNull
    @Transactional
    public AccountingPeriodResponse reopenPeriod(@NonNull String periodCode, @NonNull String justification) {
        YearMonth yearMonth = parsePeriodCode(periodCode);
        String canonicalCode = yearMonth.toString();

        if (justification.isBlank()) {
            throw new IllegalArgumentException("A non-blank justification is required to reopen a period");
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
     * Concurrency-safe: the unique period_code constraint plus duplicate-key
     * catch-and-re-read resolves the auto-provision race.
     */
    private AccountingPeriod findOrProvision(YearMonth yearMonth) {
        String periodCode = yearMonth.toString();
        return periodRepository.findByPeriodCode(periodCode).orElseGet(() -> {
            AccountingPeriod period = newOpenPeriod(yearMonth);
            try {
                AccountingPeriod saved = periodRepository.saveAndFlush(period);
                log.info("Auto-provisioned OPEN accounting period {}", periodCode);
                return saved;
            } catch (DataIntegrityViolationException e) {
                log.debug("Concurrent auto-provision of period {}; re-reading", periodCode);
                return periodRepository.findByPeriodCode(periodCode).orElseThrow(() -> e);
            }
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

    private AccountingPeriod newOpenPeriod(YearMonth yearMonth) {
        AccountingPeriod period = new AccountingPeriod();
        period.setPeriodCode(yearMonth.toString());
        period.setStartDate(yearMonth.atDay(1));
        period.setEndDate(yearMonth.atEndOfMonth());
        period.setStatus(AccountingPeriodStatus.OPEN);
        return period;
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
        AccountingAuditLog auditLog = new AccountingAuditLog();
        auditLog.setEntityType(AUDIT_ENTITY_TYPE);
        auditLog.setEntityId(period.getPeriodId());
        auditLog.setOperation(operation);
        auditLog.setUserId(actor);
        auditLog.setJustification(justification);
        auditLog.setOldValue(oldStatus.name());
        auditLog.setNewValue(newStatus.name());
        auditLogRepository.save(auditLog);
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
            throw new IllegalArgumentException("Invalid period code '" + periodCode + "': expected format YYYY-MM", e);
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
