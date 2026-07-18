package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.EventOutbox;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.JournalEntryNotReversibleException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.EventOutboxRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2-backed behavior tests for the journal-entry reversal lifecycle
 * (story A3, issue #943): original POSTED → REVERSED with {@code reversedAt}
 * and bidirectional linkage, reversal saved immediately POSTED with its own
 * entry number and inverted lines, reversal-date resolution against open
 * accounting periods, audit trail, and the double-reversal guard.
 *
 * <p>Deliberately NOT {@code @Transactional}: each service call must run in
 * its own committing transaction so the conditional-UPDATE reversal guard
 * ({@code JournalEntryRepository.markReversed}) and the period/sequence
 * provisioners interact exactly as in production; state is cleaned up
 * explicitly. The two-thread race test exercises the guard's update-count
 * semantics: both threads pass the service-level status check, but only one
 * UPDATE matches the POSTED row.
 *
 * <p>Historic months (2019) are used to avoid period-state collisions with
 * other test classes sharing the Spring context, except where the current
 * open period is the intended reversal-date fallback.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("JournalEntry reversal lifecycle (A3)")
class JournalEntryReversalLifecycleTest {

    @Autowired
    private JournalEntryService journalEntryService;

    @Autowired
    private AccountingPeriodService accountingPeriodService;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private AccountingSequenceRepository sequenceRepository;

    @Autowired
    private AccountingPeriodRepository periodRepository;

    @Autowired
    private AccountingAuditLogRepository auditLogRepository;

    @Autowired
    private EventOutboxRepository outboxRepository;

    @Autowired
    private GLAccountRepository glAccountRepository;

    private UUID glAccountId;

    @BeforeEach
    void setUpAccount() {
        GLAccount account = new GLAccount();
        account.setAccountCode("A3-" + UUID.randomUUID().toString().substring(0, 8));
        account.setAccountName("A3 reversal test account");
        account.setAccountType(AccountType.ASSET);
        account.setActivationDate(LocalDateTime.of(2015, 1, 1, 0, 0));
        account.setCreatedBy("a3-test");
        account.setModifiedBy("a3-test");
        glAccountId = glAccountRepository.save(account).getGlAccountId();
    }

    @AfterEach
    void cleanUp() {
        // Reversal linkage is circular (original <-> reversal FKs), so null
        // the links before deleting the rows.
        List<JournalEntry> entries = journalEntryRepository.findAll();
        entries.forEach(entry -> {
            entry.setReversalJournalEntryId(null);
            entry.setReversedByJournalEntryId(null);
        });
        journalEntryRepository.saveAllAndFlush(entries);
        journalEntryRepository.deleteAll();
        sequenceRepository.deleteAll();
        periodRepository.deleteAll();
        auditLogRepository.deleteAll();
        outboxRepository.deleteAll();
        glAccountRepository.deleteById(glAccountId);
    }

    @Test
    @DisplayName("round trip: original flips to REVERSED with linkage both ways; reversal POSTED, numbered, inverted")
    void reverse_roundTrip_statusLinkageNumberingAndAudit() {
        JournalEntry original = createPosted(LocalDateTime.of(2019, 5, 10, 9, 30));
        assertThat(original.getEntryNumber()).isEqualTo("JE-201905-1");

        JournalEntry reversal =
                journalEntryService.reverseJournalEntry(original.getJournalEntryId(), "CORRECTION", null);

        // Original: REVERSED, reversedAt stamped, linked to its reversal
        JournalEntry reloadedOriginal = journalEntryService.getJournalEntry(original.getJournalEntryId());
        assertThat(reloadedOriginal.getStatus()).isEqualTo(JournalEntryStatus.REVERSED);
        assertThat(reloadedOriginal.getReversedAt()).isNotNull();
        assertThat(reloadedOriginal.getReversedByJournalEntryId()).isEqualTo(reversal.getJournalEntryId());
        assertThat(reloadedOriginal.getEntryNumber())
                .as("original keeps its posted number")
                .isEqualTo("JE-201905-1");

        // Reversal: immediately POSTED with its own number in the same open
        // month (default reversal date = original's transaction date), linked
        // back to the original, lines inverted.
        JournalEntry reloadedReversal = journalEntryService.getJournalEntry(reversal.getJournalEntryId());
        assertThat(reloadedReversal.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(reloadedReversal.getEntryNumber()).isEqualTo("JE-201905-2");
        assertThat(reloadedReversal.getPostedAt()).isNotNull();
        assertThat(reloadedReversal.getReversalJournalEntryId()).isEqualTo(original.getJournalEntryId());
        assertThat(reloadedReversal.getTransactionDate()).isEqualTo(original.getTransactionDate());
        // Line inversion asserted on the service-returned reversal (lines are
        // LAZY on a detached reload).
        assertThat(reversal.getLines()).hasSize(2);
        assertThat(reversal.getLines().get(0).getDebitAmount())
                .isEqualByComparingTo(original.getLines().get(0).getCreditAmount());
        assertThat(reversal.getLines().get(0).getCreditAmount())
                .isEqualByComparingTo(original.getLines().get(0).getDebitAmount());

        // Traceability shows the pair from both directions
        var originalTrace = journalEntryService.getJournalTraceability(original.getJournalEntryId());
        assertThat(originalTrace.getReversalJournalEntry()).isNotNull();
        assertThat(originalTrace.getReversalJournalEntry().getJournalEntryId()).isEqualTo(reversal.getJournalEntryId());

        var reversalTrace = journalEntryService.getJournalTraceability(reversal.getJournalEntryId());
        assertThat(reversalTrace.getOriginalJournalEntry()).isNotNull();
        assertThat(reversalTrace.getOriginalJournalEntry().getJournalEntryId()).isEqualTo(original.getJournalEntryId());

        // Audit row with actor (SYSTEM for the unauthenticated test flow)
        List<AccountingAuditLog> audits = auditLogRepository.findAll().stream()
                .filter(a -> "JOURNAL_ENTRY".equals(a.getEntityType()) && "REVERSE".equals(a.getOperation()))
                .toList();
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().getEntityId()).isEqualTo(original.getJournalEntryId());
        assertThat(audits.getFirst().getUserId()).isEqualTo("SYSTEM");
        assertThat(audits.getFirst().getJustification()).isEqualTo("CORRECTION");
        assertThat(audits.getFirst().getOldValue()).isEqualTo("POSTED");
        assertThat(audits.getFirst().getNewValue()).isEqualTo("REVERSED by " + reversal.getJournalEntryId());

        // Outbox domain event written in the same transaction so downstream
        // read models see the flip.
        List<EventOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        EventOutbox outboxRow = outboxRows.getFirst();
        assertThat(outboxRow.getAggregateType()).isEqualTo("JournalEntry");
        assertThat(outboxRow.getAggregateId()).isEqualTo(original.getJournalEntryId());
        assertThat(outboxRow.getEventType()).isEqualTo("JournalEntryReversed");
        assertThat(outboxRow.getPayload())
                .contains(original.getJournalEntryId().toString())
                .contains(reversal.getJournalEntryId().toString())
                .contains("JE-201905-1")
                .contains("JE-201905-2")
                .contains("2019-05-10")
                .contains("CORRECTION")
                .contains("SYSTEM");
    }

    @Test
    @DisplayName("double reversal is rejected with JE_ALREADY_REVERSED semantics")
    void reverse_twice_rejected() {
        JournalEntry original = createPosted(LocalDateTime.of(2019, 6, 12, 14, 0));
        journalEntryService.reverseJournalEntry(original.getJournalEntryId(), "FIRST", null);

        assertThatThrownBy(() -> journalEntryService.reverseJournalEntry(original.getJournalEntryId(), "SECOND", null))
                .isInstanceOf(JournalEntryNotReversibleException.class)
                .extracting(ex -> ((JournalEntryNotReversibleException) ex).getCurrentStatus())
                .isEqualTo(JournalEntryStatus.REVERSED);

        assertThat(journalEntryRepository.count())
                .as("no second reversal entry persisted")
                .isEqualTo(2);
        assertThat(outboxRepository.count())
                .as("no outbox event for the rejected second reversal")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("explicit reversalDate in an open period dates and numbers the reversal in that month")
    void reverse_explicitOpenDate_used() {
        JournalEntry original = createPosted(LocalDateTime.of(2019, 7, 3, 8, 15));

        JournalEntry reversal = journalEntryService.reverseJournalEntry(
                original.getJournalEntryId(), "RECLASS", LocalDate.of(2019, 8, 5));

        assertThat(reversal.getTransactionDate()).isEqualTo(LocalDateTime.of(2019, 8, 5, 0, 0));
        assertThat(reversal.getEntryNumber()).isEqualTo("JE-201908-1");
        assertThat(periodRepository.findByPeriodCode("2019-08"))
                .as("period row auto-provisioned for the reversal date")
                .isPresent();
    }

    @Test
    @DisplayName("explicit reversalDate in a closed period is rejected and nothing changes")
    void reverse_explicitClosedDate_rejected() {
        JournalEntry original = createPosted(LocalDateTime.of(2019, 10, 20, 11, 0));
        accountingPeriodService.closePeriod("2019-09");

        assertThatThrownBy(() -> journalEntryService.reverseJournalEntry(
                        original.getJournalEntryId(), "CORRECTION", LocalDate.of(2019, 9, 15)))
                .isInstanceOf(AccountingPeriodClosedException.class)
                .hasMessageContaining("2019-09");

        JournalEntry reloaded = journalEntryService.getJournalEntry(original.getJournalEntryId());
        assertThat(reloaded.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(reloaded.getReversedAt()).isNull();
        assertThat(journalEntryRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("default reversal date falls to the current open period when the original's period is closed")
    void reverse_defaultDate_fallsToCurrentPeriodWhenOriginalPeriodClosed() {
        JournalEntry original = createPosted(LocalDateTime.of(2019, 11, 8, 16, 45));
        accountingPeriodService.closePeriod("2019-11");

        JournalEntry reversal = journalEntryService.reverseJournalEntry(original.getJournalEntryId(), "LATE_FIX", null);

        assertThat(YearMonth.from(reversal.getTransactionDate()))
                .as("reversal dated in the current open period, not the closed original month")
                .isEqualTo(YearMonth.now());
        assertThat(reversal.getEntryNumber())
                .startsWith("JE-" + YearMonth.now().toString().replace("-", "") + "-");
        assertThat(journalEntryService
                        .getJournalEntry(original.getJournalEntryId())
                        .getStatus())
                .isEqualTo(JournalEntryStatus.REVERSED);
    }

    @Test
    @DisplayName("concurrent double reversal: exactly one wins, the loser gets the conflict, no orphan reversal")
    void reverse_concurrentRace_exactlyOneWins() throws Exception {
        JournalEntry original = createPosted(LocalDateTime.of(2019, 12, 2, 10, 0));
        UUID originalId = original.getJournalEntryId();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int successes = 0;
        int conflicts = 0;
        try {
            Future<JournalEntry> first = pool.submit(() -> reverseAfter(start, originalId, "RACE-A"));
            Future<JournalEntry> second = pool.submit(() -> reverseAfter(start, originalId, "RACE-B"));
            start.countDown();

            for (Future<JournalEntry> future : List.of(first, second)) {
                try {
                    assertThat(future.get(30, TimeUnit.SECONDS)).isNotNull();
                    successes++;
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(JournalEntryNotReversibleException.class);
                    conflicts++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes).as("exactly one reversal wins the race").isEqualTo(1);
        assertThat(conflicts).as("the loser gets the 409-mapped conflict").isEqualTo(1);

        // Exactly one reversal row committed; the loser's insert and outbox
        // event rolled back with its transaction.
        assertThat(journalEntryRepository.count()).isEqualTo(2);
        assertThat(outboxRepository.count()).isEqualTo(1);
        JournalEntry reloadedOriginal = journalEntryService.getJournalEntry(originalId);
        assertThat(reloadedOriginal.getStatus()).isEqualTo(JournalEntryStatus.REVERSED);
        assertThat(reloadedOriginal.getReversedByJournalEntryId()).isNotNull();
    }

    private JournalEntry reverseAfter(CountDownLatch start, UUID originalId, String reason)
            throws InterruptedException {
        start.await();
        return journalEntryService.reverseJournalEntry(originalId, reason, null);
    }

    private JournalEntry createPosted(LocalDateTime transactionDate) {
        JournalEntry entry = new JournalEntry();
        entry.setTransactionDate(transactionDate);
        entry.setDescription("A3 reversal test");
        entry.addLine(line(new BigDecimal("250.0000"), BigDecimal.ZERO));
        entry.addLine(line(BigDecimal.ZERO, new BigDecimal("250.0000")));
        JournalEntry draft = journalEntryService.createJournalEntry(entry);
        return journalEntryService.postJournalEntry(draft.getJournalEntryId());
    }

    private JournalEntryLine line(BigDecimal debit, BigDecimal credit) {
        JournalEntryLine line = new JournalEntryLine();
        line.setGlAccountId(glAccountId);
        line.setDebitAmount(debit);
        line.setCreditAmount(credit);
        return line;
    }
}
