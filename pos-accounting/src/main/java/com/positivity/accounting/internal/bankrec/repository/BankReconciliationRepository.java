package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import jakarta.persistence.LockModeType;
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
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * The reconciliation, row-locked ({@code FOR UPDATE}) for the rest of the transaction: submit and approve
     * re-read the live balance and the counts under this lock so a concurrent posting cannot slip between the
     * check and the approval (§4.9, §6.3, I3; S5, #2304).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM BankReconciliation r WHERE r.reconciliationId = :id")
    Optional<BankReconciliation> lockById(@Param("id") @NonNull UUID reconciliationId);

    /**
     * The reconciliations on the given accounts in the given statuses whose window contains {@code date},
     * row-locked: the ledger-change hook (§5.5) locks the {@code SUBMITTED} and {@code FINALIZED} windows a
     * posting lands in, so it serializes with an approval of the same window (I3, AC 9; S5, #2304).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM BankReconciliation r WHERE r.glAccount.glAccountId IN :accounts AND r.status IN :statuses"
            + " AND r.statementStartDate <= :date AND r.statementEndDate >= :date")
    @NonNull
    List<BankReconciliation> lockCovering(
            @Param("accounts") @NonNull Collection<UUID> glAccountIds,
            @Param("statuses") @NonNull Collection<ReconciliationStatus> statuses,
            @Param("date") @NonNull LocalDate date);

    /** The given reconciliations, row-locked, in id order (§5.5 hook; S5, #2304). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM BankReconciliation r WHERE r.reconciliationId IN :ids ORDER BY r.reconciliationId")
    @NonNull
    List<BankReconciliation> lockByIds(@Param("ids") @NonNull Collection<UUID> ids);

    /** The reconciliations of the given statements in the given statuses (predecessor lookup at approval; S5). */
    @NonNull
    List<BankReconciliation> findByStatementIdInAndStatusIn(
            @NonNull Collection<UUID> statementIds, @NonNull Collection<ReconciliationStatus> statuses);
}
