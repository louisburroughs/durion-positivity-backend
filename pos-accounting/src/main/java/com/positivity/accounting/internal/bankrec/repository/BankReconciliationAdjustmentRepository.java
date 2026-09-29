package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.enums.AdjustmentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link BankReconciliationAdjustment} rows (Story F2, issue #965; S4, #2303).
 */
public interface BankReconciliationAdjustmentRepository extends JpaRepository<BankReconciliationAdjustment, UUID> {

    @NonNull
    List<BankReconciliationAdjustment> findByReconciliation_ReconciliationId(@NonNull UUID reconciliationId);

    /**
     * Every adjustment of every reconciliation on the account, with its reconciliation fetched: the
     * postings §3.7 reads for {@code sumLateAdjustments} and {@code sumOpeningAdjustments}.
     */
    @Query("SELECT a FROM BankReconciliationAdjustment a JOIN FETCH a.reconciliation r "
            + "WHERE r.glAccount.glAccountId = :glAccountId")
    @NonNull
    List<BankReconciliationAdjustment> findAllOnAccount(@Param("glAccountId") @NonNull UUID glAccountId);

    /** The adjustment a command posted (§6.3). */
    Optional<BankReconciliationAdjustment> findByRequestId(@NonNull UUID requestId);

    /** The POSTED gap bridge of a statement, if any (§4.2). */
    boolean existsByBridgesStatementIdAndStatus(@NonNull UUID bridgesStatementId, @NonNull AdjustmentStatus status);

    Optional<BankReconciliationAdjustment> findByAdjustmentIdAndReconciliation_ReconciliationId(
            @NonNull UUID adjustmentId, @NonNull UUID reconciliationId);
}
