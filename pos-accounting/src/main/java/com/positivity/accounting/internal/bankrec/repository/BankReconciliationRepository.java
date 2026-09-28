package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link BankReconciliation} headers (Story F2, issue #965). The list
 * filters are expressed as derived queries and branched in the service, avoiding a
 * nullable-parameter JPQL filter (which is brittle for enum parameters under
 * Hibernate 6).
 */
public interface BankReconciliationRepository extends JpaRepository<BankReconciliation, UUID> {

    Page<BankReconciliation> findByGlAccount_GlAccountId(UUID glAccountId, Pageable pageable);

    Page<BankReconciliation> findByStatus(ReconciliationStatus status, Pageable pageable);

    Page<BankReconciliation> findByGlAccount_GlAccountIdAndStatus(
            UUID glAccountId, ReconciliationStatus status, Pageable pageable);

    /** Whether a reconciliation in {@code status} on the account covers {@code date} (§3.8, D10; #2301). */
    boolean existsByGlAccount_GlAccountIdAndStatusAndStatementStartDateLessThanEqualAndStatementEndDateGreaterThanEqual(
            UUID glAccountId, ReconciliationStatus status, LocalDate onOrAfterStart, LocalDate onOrBeforeEnd);

    /** The account's reconciliations in {@code status}, oldest window first (reconciled frontier, §4.1). */
    List<BankReconciliation> findByGlAccount_GlAccountIdAndStatusOrderByStatementStartDateAsc(
            UUID glAccountId, ReconciliationStatus status);

    /** The reconciliations of one statement (§6.1 statement read). */
    List<BankReconciliation> findByStatementIdOrderByStatementStartDateAsc(UUID statementId);
}
