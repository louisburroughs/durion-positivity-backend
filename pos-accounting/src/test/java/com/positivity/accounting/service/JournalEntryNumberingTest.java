package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
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
 * H2-backed behavior tests for posted-entry numbering (story A2, issue #942,
 * decision D-1): {@code JE-{YYYYMM}-{seq}} assigned at POST time from the
 * per-transaction-month {@code accounting_sequence} counter.
 *
 * <p>Deliberately NOT {@code @Transactional}: each service call must run in
 * its own committing transaction so the sequence bootstrap
 * ({@code REQUIRES_NEW}) and the same-transaction increment interact exactly
 * as in production; state is cleaned up explicitly. Concurrency (FOR UPDATE
 * serialization) is covered by the Docker-gated
 * {@code JournalEntryNumberingConcurrencyIT} and the PG16 migration
 * validation — H2 here proves the assignment semantics.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("JournalEntry posted-entry numbering (A2)")
class JournalEntryNumberingTest {

    @Autowired
    private JournalEntryService journalEntryService;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private AccountingSequenceRepository sequenceRepository;

    @Autowired
    private GLAccountRepository glAccountRepository;

    private UUID glAccountId;

    @BeforeEach
    void setUpAccount() {
        GLAccount account = new GLAccount();
        account.setAccountCode("A2-" + UUID.randomUUID().toString().substring(0, 8));
        account.setAccountName("A2 numbering test account");
        account.setAccountType(AccountType.ASSET);
        account.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        account.setCreatedBy("a2-test");
        account.setModifiedBy("a2-test");
        glAccountId = glAccountRepository.save(account).getGlAccountId();
    }

    @AfterEach
    void cleanUp() {
        journalEntryRepository.deleteAll();
        sequenceRepository.deleteAll();
        glAccountRepository.deleteById(glAccountId);
    }

    @Test
    @DisplayName("posting assigns JE-{YYYYMM}-1 then -2 sequentially within one month")
    void posting_sameMonth_assignsSequentialNumbers() {
        JournalEntry first = createDraft(LocalDateTime.of(2021, 3, 5, 10, 0));
        JournalEntry second = createDraft(LocalDateTime.of(2021, 3, 25, 16, 30));

        JournalEntry postedFirst = journalEntryService.postJournalEntry(first.getJournalEntryId());
        JournalEntry postedSecond = journalEntryService.postJournalEntry(second.getJournalEntryId());

        assertThat(postedFirst.getEntryNumber()).isEqualTo("JE-202103-1");
        assertThat(postedSecond.getEntryNumber()).isEqualTo("JE-202103-2");
    }

    @Test
    @DisplayName("different transaction months use independent sequences")
    void posting_differentMonths_independentSequences() {
        JournalEntry march = createDraft(LocalDateTime.of(2021, 4, 30, 23, 59));
        JournalEntry april = createDraft(LocalDateTime.of(2021, 5, 1, 0, 0));

        assertThat(journalEntryService
                        .postJournalEntry(march.getJournalEntryId())
                        .getEntryNumber())
                .isEqualTo("JE-202104-1");
        assertThat(journalEntryService
                        .postJournalEntry(april.getJournalEntryId())
                        .getEntryNumber())
                .isEqualTo("JE-202105-1");
    }

    @Test
    @DisplayName("drafts remain unnumbered until posted")
    void draft_remainsUnnumbered() {
        JournalEntry draft = createDraft(LocalDateTime.of(2021, 6, 10, 9, 0));

        assertThat(draft.getEntryNumber()).isNull();
        assertThat(journalEntryService
                        .getJournalEntry(draft.getJournalEntryId())
                        .getEntryNumber())
                .isNull();
        assertThat(sequenceRepository.findAll())
                .as("no sequence row consumed by drafting")
                .isEmpty();
    }

    @Test
    @DisplayName("PENDING entries cannot be posted and remain unnumbered")
    void pendingEntry_notPostable_remainsUnnumbered() {
        JournalEntry pending = createDraft(LocalDateTime.of(2021, 7, 10, 9, 0));
        pending.setStatus(JournalEntryStatus.PENDING);
        journalEntryRepository.save(pending);

        assertThatThrownBy(() -> journalEntryService.postJournalEntry(pending.getJournalEntryId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot post PENDING");

        assertThat(journalEntryService
                        .getJournalEntry(pending.getJournalEntryId())
                        .getEntryNumber())
                .isNull();
        assertThat(sequenceRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("pre-numbering POSTED entries (entryNumber null) are unaffected by new posts")
    void legacyPostedEntry_unaffectedByNewNumbering() {
        // Simulate an entry posted before A2 shipped: POSTED, no number.
        JournalEntry legacy = createDraft(LocalDateTime.of(2021, 8, 3, 8, 0));
        legacy.setStatus(JournalEntryStatus.POSTED);
        journalEntryRepository.save(legacy);

        // A new post in the same transaction month starts the sequence at 1;
        // legacy rows neither seed nor consume the counter.
        JournalEntry fresh = createDraft(LocalDateTime.of(2021, 8, 20, 11, 0));
        JournalEntry postedFresh = journalEntryService.postJournalEntry(fresh.getJournalEntryId());

        assertThat(postedFresh.getEntryNumber()).isEqualTo("JE-202108-1");
        assertThat(journalEntryService
                        .getJournalEntry(legacy.getJournalEntryId())
                        .getEntryNumber())
                .isNull();
    }

    @Test
    @DisplayName("assigned number is visible through the service read path")
    void assignedNumber_visibleViaServiceGet() {
        JournalEntry draft = createDraft(LocalDateTime.of(2021, 9, 14, 14, 45));
        journalEntryService.postJournalEntry(draft.getJournalEntryId());

        JournalEntry reloaded = journalEntryService.getJournalEntry(draft.getJournalEntryId());
        assertThat(reloaded.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(reloaded.getEntryNumber()).isEqualTo("JE-202109-1");
    }

    private JournalEntry createDraft(LocalDateTime transactionDate) {
        JournalEntry entry = new JournalEntry();
        entry.setTransactionDate(transactionDate);
        entry.setDescription("A2 numbering test");
        entry.addLine(line(new BigDecimal("100.0000"), BigDecimal.ZERO));
        entry.addLine(line(BigDecimal.ZERO, new BigDecimal("100.0000")));
        return journalEntryService.createJournalEntry(entry);
    }

    private JournalEntryLine line(BigDecimal debit, BigDecimal credit) {
        JournalEntryLine line = new JournalEntryLine();
        line.setGlAccountId(glAccountId);
        line.setDebitAmount(debit);
        line.setCreditAmount(credit);
        return line;
    }
}
