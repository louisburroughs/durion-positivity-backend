package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Repository for {@link BankReconciliation} headers (Story F2, issue #965; S4, #2303). The list filters
 * use specifications rather than a nullable-parameter JPQL query (issues #1891, #1961).
 */
public interface BankReconciliationRepository
        extends JpaRepository<BankReconciliation, UUID>, JpaSpecificationExecutor<BankReconciliation> {

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

    /** The reconciliations of one statement in the given statuses (§4.1 create rule; S4, #2303). */
    @NonNull
    List<BankReconciliation> findByStatementIdAndStatusIn(
            @NonNull UUID statementId, @NonNull Collection<ReconciliationStatus> statuses);

    /** The reconciliation a create command made (§6.3; S4). */
    Optional<BankReconciliation> findByRequestId(@NonNull UUID requestId);
}
