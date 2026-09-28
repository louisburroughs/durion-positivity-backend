package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
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
}
