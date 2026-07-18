package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.service.JournalEntryService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Concurrent-post IT for posted-entry numbering (story A2, issue #942)
 * against a real PostgreSQL: two threads posting entries in the same
 * transaction month must receive consecutive, distinct numbers because the
 * {@code accounting_sequence} row lock ({@code SELECT ... FOR UPDATE},
 * {@code AccountingSequenceRepository.findByScopeKey}) serializes assignment.
 * H2 cannot prove this — its locking model differs — so this runs the full
 * Flyway chain (V1..V13 + repeatable seed) on a Testcontainers Postgres with
 * the real service stack wired to it.
 *
 * <p>Also smoke-tests the PostgreSQL-only gap-detection query
 * ({@code findMissingEntryNumbers}): gapless after the posts, and a
 * synthetically skipped counter value is reported missing.
 *
 * <p>Skipped cleanly when Docker is unavailable ({@code disabledWithoutDocker}).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("JournalEntry numbering concurrency (A2, real Postgres)")
class JournalEntryNumberingConcurrencyIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // Schema comes from the real Flyway chain, not Hibernate DDL.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    /** Transaction month for all entries in this IT; scope key JE-202606. */
    private static final LocalDateTime TX_DATE = LocalDateTime.of(2026, 6, 15, 12, 0);

    private static final String SCOPE_KEY = "JE-202606";

    @Autowired
    private JournalEntryService journalEntryService;

    @Autowired
    private AccountingSequenceRepository sequenceRepository;

    @Autowired
    private GLAccountRepository glAccountRepository;

    private UUID glAccountId;

    @BeforeEach
    void setUpAccount() {
        GLAccount account = new GLAccount();
        account.setAccountCode("A2-IT-" + UUID.randomUUID().toString().substring(0, 8));
        account.setAccountName("A2 concurrency IT account");
        account.setAccountType(AccountType.ASSET);
        account.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        account.setCreatedBy("a2-it");
        account.setModifiedBy("a2-it");
        glAccountId = glAccountRepository.save(account).getGlAccountId();
    }

    @Test
    @DisplayName("two concurrent posts in one month get consecutive distinct numbers; gap query stays clean")
    void concurrentPosts_sameMonth_consecutiveDistinctNumbers() throws Exception {
        UUID firstDraft = createDraft().getJournalEntryId();
        UUID secondDraft = createDraft().getJournalEntryId();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> postAfter(start, firstDraft));
            Future<String> second = pool.submit(() -> postAfter(start, secondDraft));
            start.countDown();

            List<String> numbers = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            // FOR UPDATE on the sequence row serializes the two posting
            // transactions: both numbers assigned, distinct, and consecutive
            // from 1 — which thread got which is scheduling-dependent.
            assertThat(numbers).containsExactlyInAnyOrder(SCOPE_KEY + "-1", SCOPE_KEY + "-2");
        } finally {
            pool.shutdownNow();
        }

        // Gap-detection smoke (PostgreSQL-only query): no gaps after the posts.
        assertThat(sequenceRepository.findMissingEntryNumbers(SCOPE_KEY)).isEmpty();

        // Synthetic gap: pretend number 3 was handed out but never stored.
        AccountingSequence sequence = sequenceRepository.findAll().stream()
                .filter(s -> SCOPE_KEY.equals(s.getScopeKey()))
                .findFirst()
                .orElseThrow();
        assertThat(sequence.getNextValue()).isEqualTo(3L);
        sequence.setNextValue(4L);
        sequenceRepository.saveAndFlush(sequence);

        assertThat(sequenceRepository.findMissingEntryNumbers(SCOPE_KEY)).containsExactly(3L);
    }

    private String postAfter(CountDownLatch start, UUID journalEntryId) throws InterruptedException {
        start.await();
        return journalEntryService.postJournalEntry(journalEntryId).getEntryNumber();
    }

    private JournalEntry createDraft() {
        JournalEntry entry = new JournalEntry();
        entry.setTransactionDate(TX_DATE);
        entry.setDescription("A2 concurrency IT");
        entry.addLine(line(new BigDecimal("42.0000"), BigDecimal.ZERO));
        entry.addLine(line(BigDecimal.ZERO, new BigDecimal("42.0000")));
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
