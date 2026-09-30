package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * #2342: first use of a sequence scope or accounting period by several concurrent requests must
 * not need a second pooled connection per request. The pool is pinned below the number of racing
 * threads, so a {@code REQUIRES_NEW} provisioner opened while the caller held its connection would
 * starve the pool and every request would fail at the connection timeout. The insert-if-absent
 * recipe keeps each request on the one connection it already holds. Needs real PostgreSQL and
 * Docker.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@TestMethodOrder(MethodOrderer.MethodName.class)
@DisplayName("First use of a sequence scope / period does not deadlock a small pool (#2342)")
class SequenceAndPeriodFirstUseConcurrencyIT {

    private static final int THREADS = 6;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerIsolatedDatabase(registry, "first-use-concurrency");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
    }

    @Autowired
    private EventIngestionService eventIngestionService;

    @Autowired
    private JournalEntryService journalEntryService;

    @Autowired
    private AccountingSequenceRepository sequenceRepository;

    @Autowired
    private AccountingPeriodRepository periodRepository;

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("concurrent submitEvent in a never-used month scope: all succeed, AE numbers consecutive")
    void a_concurrentSubmitEvent_neverUsedScope() throws Exception {
        List<AccountingEventResponse> responses = race(index -> () -> eventIngestionService.submitEvent(Map.of(
                "eventType",
                "IT_FIRST_USE",
                "payload",
                Map.of("n", index, "run", UUID.randomUUID().toString()))));

        String scope = "AE-"
                + clock.instant().atZone(ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        List<Long> numbers = responses.stream()
                .map(r -> Long.parseLong(r.getEventReference().substring(scope.length() + 1)))
                .sorted()
                .toList();
        assertThat(numbers).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(sequenceRepository.findAll().stream().filter(s -> scope.equals(s.getScopeKey())))
                .as("exactly one sequence row for the scope")
                .hasSize(1);
    }

    @Test
    @DisplayName("concurrent postJournalEntry into a never-used month: period and JE sequence provisioned once")
    void b_concurrentPostJournalEntry_neverUsedMonth() throws Exception {
        LocalDateTime txDate = LocalDateTime.of(2026, 4, 15, 12, 0);
        GLAccount account = new GLAccount();
        account.setAccountCode("FU-IT-" + UUID.randomUUID().toString().substring(0, 8));
        account.setAccountName("first-use IT account");
        account.setAccountType(AccountType.ASSET);
        account.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        account.setCreatedBy("fu-it");
        account.setModifiedBy("fu-it");
        UUID glAccountId = glAccountRepository.save(account).getGlAccountId();

        List<UUID> drafts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            drafts.add(journalEntryService
                    .createJournalEntry(JournalEntryCreateRequest.builder()
                            .transactionDate(txDate)
                            .description("first-use IT")
                            .lines(List.of(line(glAccountId, "42.0000", "0"), line(glAccountId, "0", "42.0000")))
                            .build())
                    .getJournalEntryId());
        }
        // createJournalEntry may already have provisioned the period; the JE sequence is first used by posting.

        List<String> numbers = race(index ->
                () -> journalEntryService.postJournalEntry(drafts.get(index)).getEntryNumber());

        assertThat(numbers)
                .containsExactlyInAnyOrder(
                        "JE-202604-1", "JE-202604-2", "JE-202604-3", "JE-202604-4", "JE-202604-5", "JE-202604-6");
        assertThat(sequenceRepository.findAll().stream().filter(s -> "JE-202604".equals(s.getScopeKey())))
                .hasSize(1);
        assertThat(periodRepository.findAll().stream().filter(p -> "2026-04".equals(p.getPeriodCode())))
                .hasSize(1);
    }

    private static JournalEntryCreateRequest.JournalEntryLineRequest line(
            UUID glAccountId, String debit, String credit) {
        return JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                .glAccountId(glAccountId)
                .debitAmount(new BigDecimal(debit))
                .creditAmount(new BigDecimal(credit))
                .build();
    }

    /** Runs {@code THREADS} copies released together and returns their results; any failure fails the test. */
    private static <T> List<T> race(java.util.function.IntFunction<Callable<T>> work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                Callable<T> call = work.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
