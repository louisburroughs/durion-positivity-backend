package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.exception.HardLockDateRegressionException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
 * Serialization test for the hard-lock-date setter (post-review hardening of
 * story B2, issue #944): {@code setHardLockDate} reads the configuration row
 * with a pessimistic lock ({@code findWithLockByConfigKey}), so a concurrent
 * setter cannot slip an <em>earlier</em> date past the monotonic-forward
 * check via an unlocked read–check–save interleaving (last-writer-wins
 * regression).
 *
 * <p>Two-step choreography on H2 (which honors {@code SELECT ... FOR UPDATE}
 * within transactions): a holder transaction acquires the row lock and
 * advances the date; a second setter with an earlier date starts only after
 * the lock is held, so its own locked read must wait for the holder's commit
 * — after which the monotonic check sees the advanced date and rejects the
 * regression. Without the lock, the second setter could read the stale date,
 * pass the check, and regress the hard lock.
 *
 * <p>Deliberately NOT {@code @Transactional}: both participants must run in
 * real, independently committing transactions on separate threads.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Hard-lock date setter concurrency (B2 hardening)")
class AccountingConfigurationHardLockConcurrencyTest {

    private static final String HARD_LOCK_DATE_KEY = "HARD_LOCK_DATE";
    private static final LocalDate SEED_DATE = LocalDate.of(2020, 1, 1);
    private static final LocalDate ADVANCED_DATE = LocalDate.of(2024, 1, 1);
    private static final LocalDate STALE_EARLIER_DATE = LocalDate.of(2022, 6, 1);

    @Autowired
    private AccountingConfigurationService configurationService;

    @Autowired
    private AccountingConfigurationRepository configurationRepository;

    @Autowired
    private AccountingAuditLogRepository auditLogRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        configurationRepository.deleteAll();
        auditLogRepository.deleteAll();
    }

    @Test
    @DisplayName("a concurrent setter blocks on the row lock and cannot regress the advanced hard-lock date")
    void concurrentSetter_serializesOnRowLock_andCannotRegress() throws Exception {
        configurationService.setHardLockDate(SEED_DATE, "seed");

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

            // Holder: acquire the FOR UPDATE row lock, hold it until released,
            // then advance the hard-lock date and commit.
            Future<?> holder = executor.submit(() -> txTemplate.execute(status -> {
                AccountingConfiguration config = configurationRepository
                        .findWithLockByConfigKey(HARD_LOCK_DATE_KEY)
                        .orElseThrow();
                lockHeld.countDown();
                awaitOrFail(releaseLock);
                config.setConfigValue(ADVANCED_DATE.toString());
                configurationRepository.saveAndFlush(config);
                return null;
            }));

            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

            // Latecomer: starts strictly after the lock is held, so its locked
            // read must wait for the holder's commit.
            Future<LocalDate> latecomer =
                    executor.submit(() -> configurationService.setHardLockDate(STALE_EARLIER_DATE, "stale attempt"));

            // The latecomer is blocked on the row lock while the holder holds it.
            Thread.sleep(300);
            assertThat(latecomer.isDone()).isFalse();

            releaseLock.countDown();
            holder.get(10, TimeUnit.SECONDS);

            // After the holder commits ADVANCED_DATE, the latecomer's earlier
            // date fails the monotonic-forward check instead of regressing it.
            assertThatThrownBy(() -> latecomer.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(HardLockDateRegressionException.class);

            assertThat(configurationService.getHardLockDate()).contains(ADVANCED_DATE);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for latch", e);
        }
    }
}
