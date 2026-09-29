package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
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

    @NonNull
    Page<BankReconciliation> findByGlAccount_GlAccountId(@NonNull UUID glAccountId, @NonNull Pageable pageable);

    @NonNull
    Page<BankReconciliation> findByStatus(@NonNull ReconciliationStatus status, @NonNull Pageable pageable);

    @NonNull
    Page<BankReconciliation> findByGlAccount_GlAccountIdAndStatus(
            @NonNull UUID glAccountId, @NonNull ReconciliationStatus status, @NonNull Pageable pageable);

    /** Whether a reconciliation in {@code status} on the account covers {@code date} (§3.8, D10; #2301). */
    boolean existsByGlAccount_GlAccountIdAndStatusAndStatementStartDateLessThanEqualAndStatementEndDateGreaterThanEqual(
            @NonNull UUID glAccountId,
            @NonNull ReconciliationStatus status,
            @NonNull LocalDate onOrAfterStart,
            @NonNull LocalDate onOrBeforeEnd);

    /** The account's reconciliations in {@code status}, oldest window first (reconciled frontier, §4.1). */
    @NonNull
    List<BankReconciliation> findByGlAccount_GlAccountIdAndStatusOrderByStatementStartDateAsc(
            @NonNull UUID glAccountId, @NonNull ReconciliationStatus status);

    /** The accounts' reconciliations in {@code status}, oldest window first (the bank-account list, §4.1). */
    @NonNull
    List<BankReconciliation> findByGlAccount_GlAccountIdInAndStatusOrderByStatementStartDateAsc(
            @NonNull Collection<UUID> glAccountIds, @NonNull ReconciliationStatus status);

    /** The reconciliations of one statement (§6.1 statement read). */
    @NonNull
    List<BankReconciliation> findByStatementIdOrderByStatementStartDateAsc(@NonNull UUID statementId);
}
