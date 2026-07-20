package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.PaymentApplicationReversalGLPostingEvent;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.handler.PaymentApplicationReversalGLPostingEventHandler;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.EventOutboxRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * H2-backed behavior tests for the payment-application reversal GL posting
 * lifecycle (story C2, issue #958): reversing a payment application posts a
 * reversing journal entry against the C1 cash-receipt entry (found by its
 * posting key) via A3's {@code reverseJournalEntry}, so an apply→reverse cycle
 * nets to zero in the GL while both the original (now REVERSED) and the
 * reversing (POSTED) entries remain as history.
 *
 * <p>Deliberately NOT {@code @Transactional}: the reversal service call and the
 * idempotency-key registration must run in committing transactions so the
 * A3 double-reversal guard and the C2 idempotency check interact exactly as in
 * production; state is cleaned up explicitly. Historic months isolate period
 * state from sibling test classes sharing the Spring context.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("PaymentApplication reversal GL posting lifecycle (C2)")
class PaymentApplicationReversalGLPostingLifecycleIT {

    @Autowired
    private PaymentApplicationReversalGLPostingEventHandler handler;

    @Autowired
    private GLPostingService glPostingService;

    @Autowired
    private JournalEntryService journalEntryService;

    @Autowired
    private AccountingPeriodService accountingPeriodService;

    @Autowired
    private com.positivity.accounting.service.IdempotencyService idempotencyService;

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
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID undepositedFundsAccountId;
    private UUID arAccountId;

    @BeforeEach
    void setUpAccounts() {
        undepositedFundsAccountId = createAccount("C2-UF");
        arAccountId = createAccount("C2-AR");
    }

    @AfterEach
    void cleanUp() {
        // Reversal linkage is circular (original <-> reversal FKs); null the
        // links before deleting the rows.
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
        idempotencyKeyRepository.deleteAll();
        glAccountRepository.deleteById(undepositedFundsAccountId);
        glAccountRepository.deleteById(arAccountId);
    }

    @Test
    @DisplayName("apply→reverse nets to zero in the GL: original REVERSED + reversing POSTED, lines inverted")
    void reverse_postsSymmetricReversingEntry_netZero() {
        UUID applicationRequestId = UUID.fromString("00000000-0000-0000-0000-0000000c2001");
        JournalEntry cashReceipt = postCashReceipt(applicationRequestId, LocalDateTime.of(2019, 3, 10, 9, 30));

        handler.onPaymentApplicationReversalGLPosting(reversalEvent(applicationRequestId, "Customer disputed charge"));

        // Original cash-receipt entry flips POSTED -> REVERSED.
        JournalEntry reloadedOriginal = journalEntryService.getJournalEntry(cashReceipt.getJournalEntryId());
        assertThat(reloadedOriginal.getStatus()).isEqualTo(JournalEntryStatus.REVERSED);
        assertThat(reloadedOriginal.getReversedByJournalEntryId()).isNotNull();

        // Exactly one reversing entry, POSTED, linked back to the original.
        List<JournalEntry> all = journalEntryRepository.findAll();
        assertThat(all).hasSize(2);
        JournalEntry reversing = all.stream()
                .filter(je -> je.getReversalJournalEntryId() != null)
                .findFirst()
                .orElseThrow();
        assertThat(reversing.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(reversing.getReversalJournalEntryId()).isEqualTo(cashReceipt.getJournalEntryId());

        // GL symmetry: net (debit - credit) over ALL lines of both entries is
        // zero for every account touched — the reversing entry mirrors the
        // original line-for-line.
        Map<UUID, BigDecimal> netByAccount = netByAccountAcrossAllEntries();
        assertThat(netByAccount.get(undepositedFundsAccountId)).isEqualByComparingTo("0.00");
        assertThat(netByAccount.get(arAccountId)).isEqualByComparingTo("0.00");

        // Idempotency key recorded so replays are no-ops.
        assertThat(idempotencyService.isKeyProcessed("PAYMENT_APPLICATION_REVERSAL_GL_POSTING:" + applicationRequestId))
                .isTrue();
    }

    @Test
    @DisplayName("replayed reversal work item is idempotent: no second reversing entry")
    void reverse_replay_isIdempotent() {
        UUID applicationRequestId = UUID.fromString("00000000-0000-0000-0000-0000000c2002");
        postCashReceipt(applicationRequestId, LocalDateTime.of(2019, 4, 12, 14, 0));

        PaymentApplicationReversalGLPostingEvent event = reversalEvent(applicationRequestId, "Duplicate delivery");
        handler.onPaymentApplicationReversalGLPosting(event);
        // Replay the same work item (outbox at-least-once delivery).
        handler.onPaymentApplicationReversalGLPosting(event);

        assertThat(journalEntryRepository.count())
                .as("still exactly one original + one reversing entry after replay")
                .isEqualTo(2);
        long reversingCount = journalEntryRepository.findAll().stream()
                .filter(je -> je.getReversalJournalEntryId() != null)
                .count();
        assertThat(reversingCount).as("no double reversing JE").isEqualTo(1);
    }

    @Test
    @DisplayName("reversal whose original period is now CLOSED dates the reversing entry into the current open period"
            + " (B2 default, #944)")
    void reverse_originalPeriodClosed_fallsToCurrentOpenPeriod() {
        UUID applicationRequestId = UUID.fromString("00000000-0000-0000-0000-0000000c2003");
        JournalEntry cashReceipt = postCashReceipt(applicationRequestId, LocalDateTime.of(2019, 5, 8, 16, 45));

        // Close the original's period; the B2 reversal-date default must fall to
        // the current open period rather than failing.
        accountingPeriodService.closePeriod("2019-05");

        handler.onPaymentApplicationReversalGLPosting(reversalEvent(applicationRequestId, "Late reversal"));

        JournalEntry reloadedOriginal = journalEntryService.getJournalEntry(cashReceipt.getJournalEntryId());
        assertThat(reloadedOriginal.getStatus()).isEqualTo(JournalEntryStatus.REVERSED);

        JournalEntry reversing = journalEntryRepository.findAll().stream()
                .filter(je -> je.getReversalJournalEntryId() != null)
                .findFirst()
                .orElseThrow();
        assertThat(reversing.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(YearMonth.from(reversing.getTransactionDate()))
                .as("reversing entry dated in the current open period, not the closed original month")
                .isEqualTo(YearMonth.now());
    }

    // ===== helpers =====

    private JournalEntry postCashReceipt(UUID applicationRequestId, LocalDateTime transactionDate) {
        // Mirrors the C1 cash-receipt posting: sourceEventId == the application
        // request id (UUID form), Dr Undeposited Funds / Cr AR.
        return glPostingService.postPaymentApplication(
                applicationRequestId,
                undepositedFundsAccountId,
                arAccountId,
                new BigDecimal("500.00"),
                transactionDate,
                "AR cash receipt for payment application request " + applicationRequestId);
    }

    private PaymentApplicationReversalGLPostingEvent reversalEvent(UUID applicationRequestId, String reason) {
        return PaymentApplicationReversalGLPostingEvent.builder()
                .eventId(UUID.randomUUID())
                .applicationRequestId(applicationRequestId.toString())
                .reversalId(UUID.randomUUID())
                .paymentId(UUID.randomUUID())
                .reason(reason)
                .build();
    }

    private Map<UUID, BigDecimal> netByAccountAcrossAllEntries() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return template.execute(status -> {
            Map<UUID, BigDecimal> net = new HashMap<>();
            for (JournalEntry entry : journalEntryRepository.findAll()) {
                for (JournalEntryLine line : entry.getLines()) {
                    BigDecimal debit = line.getDebitAmount() == null ? BigDecimal.ZERO : line.getDebitAmount();
                    BigDecimal credit = line.getCreditAmount() == null ? BigDecimal.ZERO : line.getCreditAmount();
                    net.merge(line.getGlAccountId(), debit.subtract(credit), BigDecimal::add);
                }
            }
            return net;
        });
    }

    private UUID createAccount(String prefix) {
        GLAccount account = new GLAccount();
        account.setAccountCode(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        account.setAccountName(prefix + " C2 reversal test account");
        account.setAccountType(AccountType.ASSET);
        account.setActivationDate(LocalDateTime.of(2015, 1, 1, 0, 0));
        account.setCreatedBy("c2-test");
        account.setModifiedBy("c2-test");
        return glAccountRepository.save(account).getGlAccountId();
    }
}
