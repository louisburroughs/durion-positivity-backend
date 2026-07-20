package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.BankReconciliationGlMatch;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link BankReconciliationGlMatch} match linkage rows (Story F2, issue #965).
 */
public interface BankReconciliationGlMatchRepository extends JpaRepository<BankReconciliationGlMatch, UUID> {

    List<BankReconciliationGlMatch> findByReconciliationId(UUID reconciliationId);

    /** Global check: is this posted GL line already consumed by a match in any reconciliation? */
    boolean existsByGlLineId(UUID glLineId);

    List<BankReconciliationGlMatch> findByReconciliationIdAndMatchId(UUID reconciliationId, UUID matchId);

    void deleteByReconciliationIdAndMatchId(UUID reconciliationId, UUID matchId);
}
