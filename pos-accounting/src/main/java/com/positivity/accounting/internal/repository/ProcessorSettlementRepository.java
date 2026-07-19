package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ProcessorSettlement;
import com.positivity.accounting.internal.enums.SettlementStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for processor settlements (story F1c, issue #963). */
public interface ProcessorSettlementRepository extends JpaRepository<ProcessorSettlement, String> {

    /** True if a settlement with this {@code eventId} has already been ingested (idempotency). */
    boolean existsByEventId(java.util.UUID eventId);

    List<ProcessorSettlement> findByStatus(SettlementStatus status);

    Page<ProcessorSettlement> findByProviderCode(String providerCode, Pageable pageable);
}
