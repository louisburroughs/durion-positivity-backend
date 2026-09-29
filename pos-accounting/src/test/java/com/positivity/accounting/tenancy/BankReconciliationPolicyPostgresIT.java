package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.positivity.accounting.internal.bankrec.enums.BankRecClosePolicy;
import com.positivity.accounting.internal.bankrec.enums.BankRecCloseScope;
import com.positivity.accounting.internal.bankrec.service.BankRecPolicy;
import com.positivity.accounting.internal.dto.BankReconciliationPolicyRequest;
import com.positivity.accounting.internal.dto.BankReconciliationPolicyResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.accounting.internal.service.AccountingConfigurationService;
import com.positivity.accounting.internal.service.AccountingConfigurationServiceImpl;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The bank reconciliation policy PUT under concurrency (SPEC-manual-bank-reconciliation §5.2, D15; story S6,
 * #2305). Two first PUTs of a tenant both find a setting without a row, so the locked read has nothing to lock and
 * the second to commit fails the {@code (tenant_id, config_key)} unique constraint; {@code
 * RetryingAccountingConfigurationService} runs that loser once more in a fresh transaction, where it locks and
 * diffs against the winner's rows. Both PUTs apply, in commit order, each audited against the value it replaced.
 *
 * <p>Requires Docker.
 */
@DisplayName("Bank reconciliation policy PUT races (#2305)")
class BankReconciliationPolicyPostgresIT extends PostgresTenancyTestBase {

    private static final String JUSTIFICATION = "Finance sets the close policy for the first time";
    private static final String CONFIGURATION = "ACCOUNTING_CONFIGURATION";

    @Autowired
    private AccountingConfigurationService policy;

    @Autowired
    private AccountingConfigurationServiceImpl policyWithoutRetry;

    @Autowired
    private AccountingConfigurationRepository configuration;

    @Autowired
    private AccountingAuditLogRepository auditLogs;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void startWithoutPolicyRows() {
        deletePolicy();
    }

    @AfterEach
    void clear() {
        deletePolicy();
    }

    private static void deletePolicy() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        owner.update(
                "DELETE FROM accounting_audit_log WHERE tenant_id = ? AND operation = 'BANK_REC_POLICY_SET'", TENANT_A);
        owner.update(
                "DELETE FROM accounting_configuration WHERE tenant_id = ? AND config_key LIKE 'BANK_REC_%'", TENANT_A);
    }

    @Test
    @DisplayName(
            "[M] two first-time PUTs both apply: the loser of the absent-row race retries against the winner's rows")
    void firstTimePutsSerialize() throws Exception {
        BankReconciliationPolicyRequest first = request(3);
        BankReconciliationPolicyRequest second = request(7);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // The winner: its rows are inserted but not yet committed while the loser runs its own checks.
            Future<?> winner = pool.submit(() -> inTx(() -> {
                policyWithoutRetry.setBankReconciliationPolicy(first);
                entityManager.flush();
                inserted.countDown();
                release.await(20, TimeUnit.SECONDS);
                return null;
            }));
            assertThat(inserted.await(20, TimeUnit.SECONDS)).isTrue();
            Future<BankReconciliationPolicyResponse> loser =
                    pool.submit(() -> asTenant(TENANT_A, () -> policy.setBankReconciliationPolicy(second)));
            Thread.sleep(500);
            assertThat(loser.isDone())
                    .as("the loser found no row to lock and waits on the winner's uncommitted insert")
                    .isFalse();
            release.countDown();
            winner.get(20, TimeUnit.SECONDS);
            assertThat(loser.get(20, TimeUnit.SECONDS).getCloseCoverageLagDays())
                    .isEqualTo(7);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        Map<String, String> stored = inTx(() -> configuration.findByConfigKeyIn(BankRecPolicy.KEYS).stream()
                .collect(Collectors.toMap(
                        AccountingConfiguration::getConfigKey, AccountingConfiguration::getConfigValue)));
        assertThat(stored)
                .containsEntry(BankRecPolicy.CLOSE_POLICY, BankRecClosePolicy.ADVISORY.name())
                .containsEntry(BankRecPolicy.CLOSE_COVERAGE_LAG_DAYS, "7");
        assertThat(inTx(() -> policyRows(BankRecPolicy.CLOSE_COVERAGE_LAG_DAYS)))
                .as("the winner's set, then the loser's change against the winner's value")
                .extracting(AccountingAuditLog::getOldValue, AccountingAuditLog::getNewValue)
                .containsExactly(tuple("0", "3"), tuple("3", "7"));
        assertThat(inTx(() -> policyRows(BankRecPolicy.CLOSE_POLICY)))
                .as("the loser found the winner's ADVISORY already stored and wrote it no second time")
                .hasSize(1);
    }

    /** Both PUTs set ADVISORY and no threshold; they differ in the coverage lag only. */
    private static BankReconciliationPolicyRequest request(int lag) {
        BankReconciliationPolicyRequest request = new BankReconciliationPolicyRequest();
        request.setClosePolicy(BankRecClosePolicy.ADVISORY);
        request.setCloseScope(BankRecCloseScope.BANK_CASH_SUBTYPE);
        request.setCloseCoverageLagDays(lag);
        request.setAllowSelfApproval(false);
        request.setOtherApprovalThreshold(null);
        request.setJustification(JUSTIFICATION);
        return request;
    }

    private List<AccountingAuditLog> policyRows(String key) {
        UUID configId = configuration.findByConfigKey(key).orElseThrow().getConfigId();
        return auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc(CONFIGURATION, configId);
    }

    private <T> T inTx(Callable<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return asTenant(
                TENANT_A,
                () -> tx.execute(status -> {
                    try {
                        return work.call();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
    }
}
