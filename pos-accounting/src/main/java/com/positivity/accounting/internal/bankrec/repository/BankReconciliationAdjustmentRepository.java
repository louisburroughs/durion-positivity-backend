package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link BankReconciliationAdjustment} rows (Story F2, issue #965).
 */
public interface BankReconciliationAdjustmentRepository extends JpaRepository<BankReconciliationAdjustment, UUID> {

    List<BankReconciliationAdjustment> findByReconciliation_ReconciliationId(UUID reconciliationId);
}
