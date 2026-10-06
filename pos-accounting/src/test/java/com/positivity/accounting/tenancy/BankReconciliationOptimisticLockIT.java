package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.config.AccountingExceptionHandler;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.shared.error.ApiError;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code @Version} on {@code bank_reconciliation} (story S1, #2300; SPEC-manual-bank-reconciliation §6.3,
 * criterion 9): two saves of one reconciliation read at the same version — the second is refused, and the
 * module answers it 409 {@code OPTIMISTIC_LOCK}. Requires Docker.
 */
@DisplayName("Bank reconciliation optimistic locking on Postgres (#2300)")
class BankReconciliationOptimisticLockIT extends PostgresTenancyTestBase {

    /** {@code 1000 Cash} of TENANT_A, provisioned from the accounting template and found by code. */
    private UUID cashAccountId;

    @BeforeEach
    void provisionTenantA() {
        cashAccountId = provisionedAccountId(TENANT_A, "1000");
    }

    @Autowired
    private BankReconciliationRepository reconciliations;

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    private UUID reconciliationId;

    @AfterEach
    void clear() {
        TenantContext.clear();
        if (reconciliationId != null) {
            new JdbcTemplate(ownerDataSource())
                    .update("DELETE FROM bank_reconciliation WHERE reconciliation_id = ?", reconciliationId);
        }
    }

    @Test
    void aSaveAtAStaleVersionIsRefusedAndAnswers409OptimisticLock() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        reconciliationId = asTenant(
                TENANT_A,
                () -> tx.execute(status -> {
                    BankReconciliation reconciliation = new BankReconciliation();
                    reconciliation.setGlAccount(glAccounts.getReferenceById(cashAccountId));
                    reconciliation.setStatementStartDate(LocalDate.of(2042, 5, 1));
                    reconciliation.setStatementEndDate(LocalDate.of(2042, 5, 31));
                    reconciliation.setCurrency("USD");
                    reconciliation.setStatementClosingBalance(new BigDecimal("10.0000"));
                    reconciliation.setGlEndingBalance(BigDecimal.ZERO);
                    reconciliation.setDifference(new BigDecimal("10.0000"));
                    reconciliation.setStatus(ReconciliationStatus.IN_PROGRESS);
                    return reconciliations.saveAndFlush(reconciliation).getReconciliationId();
                }));

        // Two concurrent editors read the same version.
        BankReconciliation first = asTenant(
                TENANT_A, () -> reconciliations.findById(reconciliationId).orElseThrow());
        BankReconciliation second = asTenant(
                TENANT_A, () -> reconciliations.findById(reconciliationId).orElseThrow());
        assertThat(first.getVersion()).isEqualTo(second.getVersion());

        first.setDifference(new BigDecimal("1.0000"));
        asTenant(TENANT_A, () -> reconciliations.saveAndFlush(first));

        second.setDifference(new BigDecimal("2.0000"));
        assertThatThrownBy(() -> asTenant(TENANT_A, () -> reconciliations.saveAndFlush(second)))
                .isInstanceOfSatisfying(ObjectOptimisticLockingFailureException.class, stale -> {
                    ResponseEntity<ApiError> answer =
                            new AccountingExceptionHandler(clock).handleOptimisticLock(stale, null);
                    assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(answer.getBody()).isNotNull();
                    assertThat(answer.getBody().code()).isEqualTo("OPTIMISTIC_LOCK");
                });

        assertThat(asTenant(
                                TENANT_A,
                                () -> reconciliations.findById(reconciliationId).orElseThrow())
                        .getDifference())
                .as("the first save stands")
                .isEqualByComparingTo("1.0000");
    }
}
