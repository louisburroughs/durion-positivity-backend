package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.BankReconciliation;
import com.positivity.accounting.internal.enums.ReconciliationStatus;
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
}
