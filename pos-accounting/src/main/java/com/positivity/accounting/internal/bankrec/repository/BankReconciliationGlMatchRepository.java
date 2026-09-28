package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link BankReconciliationGlMatch} match linkage rows (Story F2, issue #965).
 */
public interface BankReconciliationGlMatchRepository extends JpaRepository<BankReconciliationGlMatch, UUID> {

    /** The live ledger members of a reconciliation (unmatched history excluded). */
    @NonNull
    List<BankReconciliationGlMatch> findByReconciliationIdAndActiveTrue(@NonNull UUID reconciliationId);

    /** Global check: is this posted GL line already in a live match in any reconciliation? */
    boolean existsByGlLineIdAndActiveTrue(@NonNull UUID glLineId);

    @NonNull
    List<BankReconciliationGlMatch> findByMatchIdAndActiveTrue(@NonNull UUID matchId);
}
