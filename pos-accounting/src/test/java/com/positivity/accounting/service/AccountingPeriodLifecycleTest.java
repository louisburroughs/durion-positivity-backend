package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.config.TestSecurityConfig;
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
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * H2-backed lifecycle tests for the AccountingPeriod close/reopen service
 * (story B1, issue #937).
 *
 * Covers auto-provision-then-close, the DRAFT journal entry close gate,
 * state-conflict rejection, mandatory reopen justification, and audit rows
 * written with the acting user (ADR-0018).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@Import(TestSecurityConfig.class)
@DisplayName("AccountingPeriod Lifecycle Tests (B1)")
class AccountingPeriodLifecycleTest {

    private static final String ACTOR = "controller-user";
    private static final String AUDIT_ENTITY_TYPE = "ACCOUNTING_PERIOD";

    @Autowired
    private AccountingPeriodService periodService;

    @Autowired
    private AccountingPeriodRepository periodRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private AccountingAuditLogRepository auditLogRepository;

    /** A past month guaranteed to have started. */
    private YearMonth pastMonth;

    @BeforeEach
    void setUp() {
        pastMonth = YearMonth.now().minusMonths(3);

        TestingAuthenticationToken authentication = new TestingAuthenticationToken(ACTOR, null);
        authentication.setAuthenticated(true);
        authentication.setDetails(Map.of("username", ACTOR));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private JournalEntry saveJournalEntry(YearMonth month, int dayOfMonth, JournalEntryStatus status) {
        JournalEntry entry = new JournalEntry();
        entry.setStatus(status);
        entry.setTransactionDate(month.atDay(dayOfMonth).atStartOfDay());
        entry.setDescription("B1 lifecycle test entry");
        return journalEntryRepository.saveAndFlush(entry);
    }

    private List<AccountingAuditLog> auditRowsFor(UUID periodId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampAsc(AUDIT_ENTITY_TYPE, periodId);
    }

    // ===== OPEN CHECK =====

    @Test
    @DisplayName("isPeriodOpen - missing period row counts as OPEN (string and date overloads)")
    void isPeriodOpen_missingRow_isOpen() {
        assertThat(periodRepository.findByPeriodCode(pastMonth.toString())).isEmpty();

        assertThat(periodService.isPeriodOpen(pastMonth.toString())).isTrue();
        assertThat(periodService.isPeriodOpen(pastMonth.atDay(15))).isTrue();
    }

    // ===== AUTO-PROVISION =====

    @Test
    @DisplayName("ensurePeriodExists - provisions an OPEN row with month boundaries, idempotently")
    void ensurePeriodExists_provisionsOpenRow() {
        AccountingPeriodResponse first = periodService.ensurePeriodExists(pastMonth.atDay(10));

        assertThat(first.getPeriodCode()).isEqualTo(pastMonth.toString());
        assertThat(first.getStatus()).isEqualTo(AccountingPeriodStatus.OPEN);
        assertThat(first.getStartDate()).isEqualTo(pastMonth.atDay(1));
        assertThat(first.getEndDate()).isEqualTo(pastMonth.atEndOfMonth());

        AccountingPeriodResponse second = periodService.ensurePeriodExists(pastMonth.atDay(20));
        assertThat(second.getPeriodId()).isEqualTo(first.getPeriodId());
        assertThat(periodRepository.count()).isEqualTo(1);
    }

    // ===== CLOSE =====

    @Test
    @DisplayName("closePeriod - auto-provisions a started month and closes it with actor + audit row")
    void closePeriod_autoProvisionThenClose() {
        AccountingPeriodResponse closed = periodService.closePeriod(pastMonth.toString());

        assertThat(closed.getStatus()).isEqualTo(AccountingPeriodStatus.CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(closed.getClosedBy()).isEqualTo(ACTOR);

        AccountingPeriod row =
                periodRepository.findByPeriodCode(pastMonth.toString()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(AccountingPeriodStatus.CLOSED);
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getCreatedBy()).isEqualTo(ACTOR);

        List<AccountingAuditLog> auditRows = auditRowsFor(closed.getPeriodId());
        assertThat(auditRows).hasSize(1);
        AccountingAuditLog auditRow = auditRows.getFirst();
        assertThat(auditRow.getOperation()).isEqualTo("PERIOD_CLOSE");
        assertThat(auditRow.getUserId()).isEqualTo(ACTOR);
        assertThat(auditRow.getOldValue()).isEqualTo("OPEN");
        assertThat(auditRow.getNewValue()).isEqualTo("CLOSED");
        assertThat(auditRow.getTimestamp()).isNotNull();

        assertThat(periodService.isPeriodOpen(pastMonth.toString())).isFalse();
        assertThat(periodService.isPeriodOpen(pastMonth.atDay(5))).isFalse();
    }

    @Test
    @DisplayName("closePeriod - closing an already-CLOSED period is a state conflict")
    void closePeriod_alreadyClosed_conflicts() {
        periodService.closePeriod(pastMonth.toString());

        assertThatThrownBy(() -> periodService.closePeriod(pastMonth.toString()))
                .isInstanceOf(AccountingPeriodStateException.class)
                .hasMessageContaining("already CLOSED");

        // No second audit row was written
        UUID periodId = periodRepository
                .findByPeriodCode(pastMonth.toString())
                .orElseThrow()
                .getPeriodId();
        assertThat(auditRowsFor(periodId)).hasSize(1);
    }

    @Test
    @DisplayName("closePeriod - DRAFT entries dated inside the period block close and are listed")
    void closePeriod_draftEntriesInside_blocked() {
        JournalEntry draftInside = saveJournalEntry(pastMonth, 10, JournalEntryStatus.DRAFT);
        JournalEntry secondDraftInside = saveJournalEntry(pastMonth, 25, JournalEntryStatus.DRAFT);
        // Non-blocking neighbors: POSTED inside, DRAFT in the adjacent month
        saveJournalEntry(pastMonth, 12, JournalEntryStatus.POSTED);
        saveJournalEntry(pastMonth.plusMonths(1), 3, JournalEntryStatus.DRAFT);

        assertThatThrownBy(() -> periodService.closePeriod(pastMonth.toString()))
                .isInstanceOf(PeriodCloseBlockedException.class)
                .satisfies(ex -> assertThat(((PeriodCloseBlockedException) ex).getDraftJournalEntryIds())
                        .containsExactlyInAnyOrder(
                                draftInside.getJournalEntryId(), secondDraftInside.getJournalEntryId()));

        // Period stays OPEN and no audit row is written
        AccountingPeriod row =
                periodRepository.findByPeriodCode(pastMonth.toString()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(AccountingPeriodStatus.OPEN);
        assertThat(auditRowsFor(row.getPeriodId())).isEmpty();
    }

    @Test
    @DisplayName("closePeriod - succeeds once blocking drafts are posted")
    void closePeriod_afterDraftPosted_succeeds() {
        JournalEntry draft = saveJournalEntry(pastMonth, 10, JournalEntryStatus.DRAFT);

        assertThatThrownBy(() -> periodService.closePeriod(pastMonth.toString()))
                .isInstanceOf(PeriodCloseBlockedException.class);

        draft.setStatus(JournalEntryStatus.POSTED);
        journalEntryRepository.saveAndFlush(draft);

        AccountingPeriodResponse closed = periodService.closePeriod(pastMonth.toString());
        assertThat(closed.getStatus()).isEqualTo(AccountingPeriodStatus.CLOSED);
    }

    // ===== REOPEN =====

    @Test
    @DisplayName("reopenPeriod - CLOSED -> OPEN with justification, actor, and audit row")
    void reopenPeriod_success() {
        periodService.closePeriod(pastMonth.toString());

        AccountingPeriodResponse reopened =
                periodService.reopenPeriod(pastMonth.toString(), "Late vendor bill for " + pastMonth);

        assertThat(reopened.getStatus()).isEqualTo(AccountingPeriodStatus.OPEN);
        assertThat(reopened.getReopenedAt()).isNotNull();
        assertThat(reopened.getReopenedBy()).isEqualTo(ACTOR);
        assertThat(reopened.getReopenJustification()).contains("Late vendor bill");
        // close metadata from the previous close is retained for audit history
        assertThat(reopened.getClosedAt()).isNotNull();
        assertThat(reopened.getClosedBy()).isEqualTo(ACTOR);

        assertThat(periodService.isPeriodOpen(pastMonth.toString())).isTrue();

        List<AccountingAuditLog> auditRows = auditRowsFor(reopened.getPeriodId());
        assertThat(auditRows).hasSize(2);
        AccountingAuditLog reopenRow = auditRows.getLast();
        assertThat(reopenRow.getOperation()).isEqualTo("PERIOD_REOPEN");
        assertThat(reopenRow.getUserId()).isEqualTo(ACTOR);
        assertThat(reopenRow.getJustification()).contains("Late vendor bill");
        assertThat(reopenRow.getOldValue()).isEqualTo("CLOSED");
        assertThat(reopenRow.getNewValue()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("reopenPeriod - reopening an OPEN period is a state conflict")
    void reopenPeriod_alreadyOpen_conflicts() {
        periodService.ensurePeriodExists(pastMonth.atDay(1));

        assertThatThrownBy(() -> periodService.reopenPeriod(pastMonth.toString(), "no-op reopen"))
                .isInstanceOf(AccountingPeriodStateException.class)
                .hasMessageContaining("already OPEN");
    }

    @Test
    @DisplayName("reopenPeriod - unknown period is not found")
    void reopenPeriod_unknownPeriod_notFound() {
        assertThatThrownBy(() -> periodService.reopenPeriod("2019-01", "reopen the archives"))
                .isInstanceOf(AccountingPeriodNotFoundException.class);
    }

    @Test
    @DisplayName("reopenPeriod - blank justification is rejected and state is unchanged")
    void reopenPeriod_blankJustification_rejected() {
        periodService.closePeriod(pastMonth.toString());

        assertThatThrownBy(() -> periodService.reopenPeriod(pastMonth.toString(), " "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(periodService.isPeriodOpen(pastMonth.toString())).isFalse();
    }

    // ===== LIST =====

    @Test
    @DisplayName("listPeriods - returns all periods ordered by period code descending")
    void listPeriods_orderedDescending() {
        periodService.ensurePeriodExists(pastMonth.atDay(1));
        periodService.ensurePeriodExists(pastMonth.minusMonths(1).atDay(1));
        periodService.ensurePeriodExists(pastMonth.plusMonths(1).atDay(1));

        List<AccountingPeriodResponse> periods = periodService.listPeriods();

        assertThat(periods).hasSize(3);
        assertThat(periods)
                .extracting(AccountingPeriodResponse::getPeriodCode)
                .containsExactly(
                        pastMonth.plusMonths(1).toString(),
                        pastMonth.toString(),
                        pastMonth.minusMonths(1).toString());
    }
}
