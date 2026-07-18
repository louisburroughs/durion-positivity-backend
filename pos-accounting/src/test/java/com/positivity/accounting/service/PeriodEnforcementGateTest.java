package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2-backed matrix tests for period enforcement across all posting paths
 * (story B2, issue #944, decision D-7).
 *
 * <p>Paths under test: manual post ({@code postJournalEntry}), reversal
 * ({@code reverseJournalEntry}), credit-memo GL posting
 * ({@code GLPostingService.postCreditMemoReversal}), and payment-application
 * GL posting ({@code GLPostingService.postPaymentApplication}) — the last two
 * funnel through {@code postJournalEntry}, proving the single choke point.
 * The engine autoPost path is covered at the orchestrator level in
 * {@code PostingEngineOrchestratorTest} (Period Gate Pre-Check Tests).
 *
 * <p>Matrix per path: open period → succeeds; CLOSED → rejected
 * (PERIOD_CLOSED); CLOSED + {@code accounting:period:override} authority +
 * justification → succeeds with a PERIOD_OVERRIDE_POST audit row;
 * hard-locked → rejected regardless of override (PERIOD_HARD_LOCKED).
 *
 * <p>Deliberately NOT {@code @Transactional} (mirrors
 * {@code JournalEntryReversalLifecycleTest}): period/sequence provisioning
 * commits in REQUIRES_NEW transactions, so state is cleaned up explicitly.
 * Historic months (2018) avoid period-state collisions with other test
 * classes sharing the context; the GL-posting-service scenarios necessarily
 * use the current month (those services date entries "now") and restore it.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Period enforcement gate matrix (B2)")
class PeriodEnforcementGateTest {

    private static final String ACTOR = "b2-user";
    private static final String OVERRIDE_AUTHORITY = "accounting:period:override";
    private static final String JUSTIFICATION = "Late adjustment approved by controller";

    @Autowired
    private JournalEntryService journalEntryService;

    @Autowired
    private GLPostingService glPostingService;

    @Autowired
    private AccountingPeriodService accountingPeriodService;

    @Autowired
    private AccountingConfigurationService configurationService;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private AccountingPeriodRepository periodRepository;

    @Autowired
    private AccountingSequenceRepository sequenceRepository;

    @Autowired
    private AccountingAuditLogRepository auditLogRepository;

    @Autowired
    private AccountingConfigurationRepository configurationRepository;

    @Autowired
    private EventOutboxRepository outboxRepository;

    @Autowired
    private GLAccountRepository glAccountRepository;

    private UUID glAccountId;

    @BeforeEach
    void setUp() {
        GLAccount account = new GLAccount();
        account.setAccountCode("B2-" + UUID.randomUUID().toString().substring(0, 8));
        account.setAccountName("B2 period gate test account");
        account.setAccountType(AccountType.ASSET);
        account.setActivationDate(LocalDateTime.of(2015, 1, 1, 0, 0));
        account.setCreatedBy("b2-test");
        account.setModifiedBy("b2-test");
        glAccountId = glAccountRepository.save(account).getGlAccountId();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        // Null circular reversal links before deleting journal entries.
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
        configurationRepository.deleteAll();
        outboxRepository.deleteAll();
        glAccountRepository.deleteById(glAccountId);
    }

    private void authenticate(String... authorities) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(ACTOR, null, authorities);
        authentication.setDetails(Map.of("username", ACTOR));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private JournalEntry createDraft(LocalDateTime transactionDate) {
        JournalEntry entry = new JournalEntry();
        entry.setTransactionDate(transactionDate);
        entry.setDescription("B2 period gate test");
        entry.addLine(line(new BigDecimal("100.0000"), BigDecimal.ZERO));
        entry.addLine(line(BigDecimal.ZERO, new BigDecimal("100.0000")));
        return journalEntryService.createJournalEntry(entry);
    }

    private JournalEntryLine line(BigDecimal debit, BigDecimal credit) {
        JournalEntryLine journalEntryLine = new JournalEntryLine();
        journalEntryLine.setGlAccountId(glAccountId);
        journalEntryLine.setDebitAmount(debit);
        journalEntryLine.setCreditAmount(credit);
        return journalEntryLine;
    }

    private List<AccountingAuditLog> overrideAuditRowsFor(UUID journalEntryId) {
        return auditLogRepository.findAll().stream()
                .filter(a -> "JOURNAL_ENTRY".equals(a.getEntityType())
                        && "PERIOD_OVERRIDE_POST".equals(a.getOperation())
                        && journalEntryId.equals(a.getEntityId()))
                .toList();
    }

    // ===== MANUAL POST =====

    @Nested
    @DisplayName("manual post (postJournalEntry)")
    class ManualPost {

        @Test
        @DisplayName("open period: posts, and the missing period row is auto-provisioned OPEN")
        void openPeriod_postsAndAutoProvisions() {
            assertThat(periodRepository.findByPeriodCode("2018-01")).isEmpty();

            JournalEntry draft = createDraft(LocalDateTime.of(2018, 1, 10, 9, 0));
            JournalEntry posted = journalEntryService.postJournalEntry(draft.getJournalEntryId());

            assertThat(posted.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
            assertThat(posted.getEntryNumber()).isEqualTo("JE-201801-1");
            assertThat(periodRepository.findByPeriodCode("2018-01"))
                    .isPresent()
                    .get()
                    .satisfies(period -> assertThat(period.getStatus()).isEqualTo(AccountingPeriodStatus.OPEN));
        }

        @Test
        @DisplayName("closed period: draft creation stays allowed, posting is rejected with PERIOD_CLOSED")
        void closedPeriod_rejected() {
            accountingPeriodService.closePeriod("2018-02");

            // Drafts gate at posting time (B1/B2), not at creation.
            JournalEntry draft = createDraft(LocalDateTime.of(2018, 2, 14, 9, 0));
            assertThat(draft.getStatus()).isEqualTo(JournalEntryStatus.DRAFT);

            assertThatThrownBy(() -> journalEntryService.postJournalEntry(draft.getJournalEntryId()))
                    .isInstanceOf(AccountingPeriodClosedException.class)
                    .hasMessageContaining("2018-02");

            JournalEntry reloaded = journalEntryService.getJournalEntry(draft.getJournalEntryId());
            assertThat(reloaded.getStatus()).isEqualTo(JournalEntryStatus.DRAFT);
            assertThat(reloaded.getEntryNumber()).isNull();
        }

        @Test
        @DisplayName("closed period + override authority + justification: posts with PERIOD_OVERRIDE_POST audit row")
        void closedPeriod_withOverride_succeeds() {
            accountingPeriodService.closePeriod("2018-03");
            JournalEntry draft = createDraft(LocalDateTime.of(2018, 3, 5, 9, 0));

            authenticate(OVERRIDE_AUTHORITY);
            JournalEntry posted = journalEntryService.postJournalEntry(draft.getJournalEntryId(), JUSTIFICATION);

            assertThat(posted.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
            assertThat(posted.getEntryNumber()).isEqualTo("JE-201803-1");

            List<AccountingAuditLog> auditRows = overrideAuditRowsFor(draft.getJournalEntryId());
            assertThat(auditRows).hasSize(1);
            AccountingAuditLog auditRow = auditRows.getFirst();
            assertThat(auditRow.getUserId()).isEqualTo(ACTOR);
            assertThat(auditRow.getJustification()).isEqualTo(JUSTIFICATION);
            assertThat(auditRow.getNewValue()).contains("2018-03");
        }

        @Test
        @DisplayName("closed period + justification without the override authority: rejected")
        void closedPeriod_justificationWithoutAuthority_rejected() {
            accountingPeriodService.closePeriod("2018-04");
            JournalEntry draft = createDraft(LocalDateTime.of(2018, 4, 5, 9, 0));

            authenticate(); // authenticated, but no override authority
            assertThatThrownBy(() -> journalEntryService.postJournalEntry(draft.getJournalEntryId(), JUSTIFICATION))
                    .isInstanceOf(AccountingPeriodClosedException.class)
                    .hasMessageContaining(OVERRIDE_AUTHORITY);

            assertThat(overrideAuditRowsFor(draft.getJournalEntryId())).isEmpty();
        }

        @Test
        @DisplayName("closed period + override authority without justification: rejected")
        void closedPeriod_authorityWithoutJustification_rejected() {
            accountingPeriodService.closePeriod("2018-05");
            JournalEntry draft = createDraft(LocalDateTime.of(2018, 5, 5, 9, 0));

            authenticate(OVERRIDE_AUTHORITY);
            assertThatThrownBy(() -> journalEntryService.postJournalEntry(draft.getJournalEntryId(), "  "))
                    .isInstanceOf(AccountingPeriodClosedException.class)
                    .hasMessageContaining("2018-05");
        }

        @Test
        @DisplayName("hard-locked date: rejected regardless of override, even in an OPEN period")
        void hardLocked_rejectedRegardlessOfOverride() {
            JournalEntry draft = createDraft(LocalDateTime.of(2018, 6, 20, 9, 0));
            configurationService.setHardLockDate(LocalDate.of(2018, 7, 1), "Fiscal 2018-H1 finalized");

            authenticate(OVERRIDE_AUTHORITY);
            assertThatThrownBy(() -> journalEntryService.postJournalEntry(draft.getJournalEntryId(), JUSTIFICATION))
                    .isInstanceOf(AccountingPeriodHardLockedException.class)
                    .hasMessageContaining("2018-07-01");

            assertThat(journalEntryService
                            .getJournalEntry(draft.getJournalEntryId())
                            .getStatus())
                    .isEqualTo(JournalEntryStatus.DRAFT);
        }

        @Test
        @DisplayName("hard lock boundary: a date on the hard-lock date itself still posts")
        void hardLockBoundary_onDatePosts() {
            configurationService.setHardLockDate(LocalDate.of(2018, 8, 1), "Boundary check");

            JournalEntry draft = createDraft(LocalDateTime.of(2018, 8, 1, 0, 0));
            JournalEntry posted = journalEntryService.postJournalEntry(draft.getJournalEntryId());

            assertThat(posted.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        }
    }

    // ===== REVERSAL =====

    @Nested
    @DisplayName("reversal (reverseJournalEntry)")
    class Reversal {

        private JournalEntry createPosted(LocalDateTime transactionDate) {
            JournalEntry draft = createDraft(transactionDate);
            return journalEntryService.postJournalEntry(draft.getJournalEntryId());
        }

        @Test
        @DisplayName("closed period + override authority + justification: reversal posts into the closed month")
        void closedPeriod_withOverride_succeeds() {
            JournalEntry original = createPosted(LocalDateTime.of(2018, 9, 10, 9, 0));
            accountingPeriodService.closePeriod("2018-09");

            authenticate(OVERRIDE_AUTHORITY);
            JournalEntry reversal = journalEntryService.reverseJournalEntry(
                    original.getJournalEntryId(), "CORRECTION", LocalDate.of(2018, 9, 15), JUSTIFICATION);

            assertThat(reversal.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
            assertThat(reversal.getTransactionDate()).isEqualTo(LocalDateTime.of(2018, 9, 15, 0, 0));
            assertThat(reversal.getEntryNumber()).isEqualTo("JE-201809-2");
            assertThat(journalEntryService
                            .getJournalEntry(original.getJournalEntryId())
                            .getStatus())
                    .isEqualTo(JournalEntryStatus.REVERSED);

            List<AccountingAuditLog> auditRows = overrideAuditRowsFor(reversal.getJournalEntryId());
            assertThat(auditRows).hasSize(1);
            assertThat(auditRows.getFirst().getUserId()).isEqualTo(ACTOR);
            assertThat(auditRows.getFirst().getJustification()).isEqualTo(JUSTIFICATION);
            assertThat(auditRows.getFirst().getNewValue()).contains("2018-09");
        }

        @Test
        @DisplayName("closed period without override: rejected with PERIOD_CLOSED and nothing changes")
        void closedPeriod_withoutOverride_rejected() {
            JournalEntry original = createPosted(LocalDateTime.of(2018, 10, 10, 9, 0));
            accountingPeriodService.closePeriod("2018-10");

            assertThatThrownBy(() -> journalEntryService.reverseJournalEntry(
                            original.getJournalEntryId(), "CORRECTION", LocalDate.of(2018, 10, 15), null))
                    .isInstanceOf(AccountingPeriodClosedException.class)
                    .hasMessageContaining("2018-10");

            assertThat(journalEntryService
                            .getJournalEntry(original.getJournalEntryId())
                            .getStatus())
                    .isEqualTo(JournalEntryStatus.POSTED);
            assertThat(journalEntryRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("hard-locked reversal date: rejected regardless of override")
        void hardLocked_rejectedRegardlessOfOverride() {
            JournalEntry original = createPosted(LocalDateTime.of(2018, 11, 10, 9, 0));
            configurationService.setHardLockDate(LocalDate.of(2018, 12, 1), "Fiscal 2018 finalized");

            authenticate(OVERRIDE_AUTHORITY);
            assertThatThrownBy(() -> journalEntryService.reverseJournalEntry(
                            original.getJournalEntryId(), "CORRECTION", LocalDate.of(2018, 11, 20), JUSTIFICATION))
                    .isInstanceOf(AccountingPeriodHardLockedException.class)
                    .hasMessageContaining("2018-12-01");

            assertThat(journalEntryService
                            .getJournalEntry(original.getJournalEntryId())
                            .getStatus())
                    .isEqualTo(JournalEntryStatus.POSTED);
            assertThat(journalEntryRepository.count()).isEqualTo(1);
        }
    }

    // ===== CREDIT MEMO GL POSTING =====

    @Nested
    @DisplayName("credit-memo GL posting (GLPostingService.postCreditMemoReversal)")
    class CreditMemoPosting {

        private JournalEntry postCreditMemo(String overrideJustification) {
            return glPostingService.postCreditMemoReversal(
                    UUID.randomUUID(),
                    glAccountId,
                    glAccountId,
                    glAccountId,
                    new BigDecimal("90.0000"),
                    new BigDecimal("10.0000"),
                    "B2 credit memo test",
                    false,
                    null,
                    overrideJustification);
        }

        @Test
        @DisplayName("open (current) period: posts")
        void openPeriod_posts() {
            JournalEntry posted = postCreditMemo(null);
            assertThat(posted.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        }

        @Test
        @DisplayName("closed current period: rejected with PERIOD_CLOSED")
        void closedPeriod_rejected() {
            accountingPeriodService.closePeriod(YearMonth.now().toString());

            assertThatThrownBy(() -> postCreditMemo(null))
                    .isInstanceOf(AccountingPeriodClosedException.class)
                    .hasMessageContaining(YearMonth.now().toString());
        }

        @Test
        @DisplayName("closed current period + override: posts with audit row")
        void closedPeriod_withOverride_succeeds() {
            accountingPeriodService.closePeriod(YearMonth.now().toString());

            authenticate(OVERRIDE_AUTHORITY);
            JournalEntry posted = postCreditMemo(JUSTIFICATION);

            assertThat(posted.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
            assertThat(overrideAuditRowsFor(posted.getJournalEntryId())).hasSize(1);
        }

        @Test
        @DisplayName("hard-locked: rejected regardless of override")
        void hardLocked_rejectedRegardlessOfOverride() {
            configurationService.setHardLockDate(LocalDate.now().plusDays(1), "Lock everything to date");

            authenticate(OVERRIDE_AUTHORITY);
            assertThatThrownBy(() -> postCreditMemo(JUSTIFICATION))
                    .isInstanceOf(AccountingPeriodHardLockedException.class);
        }
    }

    // ===== PAYMENT APPLICATION GL POSTING =====

    @Nested
    @DisplayName("payment-application GL posting (GLPostingService.postPaymentApplication)")
    class PaymentApplicationPosting {

        private JournalEntry postPaymentApplication(String overrideJustification) {
            return glPostingService.postPaymentApplication(
                    UUID.randomUUID(),
                    glAccountId,
                    glAccountId,
                    new BigDecimal("55.0000"),
                    "B2 payment application test",
                    overrideJustification);
        }

        @Test
        @DisplayName("open (current) period: posts")
        void openPeriod_posts() {
            JournalEntry posted = postPaymentApplication(null);
            assertThat(posted.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        }

        @Test
        @DisplayName("closed current period: rejected with PERIOD_CLOSED")
        void closedPeriod_rejected() {
            accountingPeriodService.closePeriod(YearMonth.now().toString());

            assertThatThrownBy(() -> postPaymentApplication(null))
                    .isInstanceOf(AccountingPeriodClosedException.class)
                    .hasMessageContaining(YearMonth.now().toString());
        }

        @Test
        @DisplayName("closed current period + override: posts with audit row")
        void closedPeriod_withOverride_succeeds() {
            accountingPeriodService.closePeriod(YearMonth.now().toString());

            authenticate(OVERRIDE_AUTHORITY);
            JournalEntry posted = postPaymentApplication(JUSTIFICATION);

            assertThat(posted.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
            assertThat(overrideAuditRowsFor(posted.getJournalEntryId())).hasSize(1);
        }

        @Test
        @DisplayName("hard-locked: rejected regardless of override")
        void hardLocked_rejectedRegardlessOfOverride() {
            configurationService.setHardLockDate(LocalDate.now().plusDays(1), "Lock everything to date");

            authenticate(OVERRIDE_AUTHORITY);
            assertThatThrownBy(() -> postPaymentApplication(JUSTIFICATION))
                    .isInstanceOf(AccountingPeriodHardLockedException.class);
        }
    }
}
