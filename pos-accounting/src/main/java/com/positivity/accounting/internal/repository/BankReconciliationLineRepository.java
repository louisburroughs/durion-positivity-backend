package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.BankReconciliationLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link BankReconciliationLine} imported statement lines (Story F2, issue #965).
 */
public interface BankReconciliationLineRepository extends JpaRepository<BankReconciliationLine, UUID> {

    List<BankReconciliationLine> findByReconciliation_ReconciliationId(UUID reconciliationId);

    List<BankReconciliationLine> findByReconciliation_ReconciliationIdAndMatchId(UUID reconciliationId, UUID matchId);
}
