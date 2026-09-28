package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingEvent;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Repository for AccountingEvent entity.
 * Supports CRUD, specification-based queries and source-system pagination.
 */
public interface AccountingEventRepository
        extends JpaRepository<AccountingEvent, UUID>, JpaSpecificationExecutor<AccountingEvent> {

    /**
     * Find accounting events by source system.
     * Uses indexed column for efficient querying.
     */
    Page<AccountingEvent> findBySourceSystem(String sourceSystem, Pageable pageable);

    /**
     * Whether an ingestion record already holds this fact for this reason, so a redelivered fact
     * held for its currency does not write a second record (issue #2312).
     */
    boolean existsByEventTypeAndDomainKeyIdAndFailureReasonCode(
            String eventType, String domainKeyId, String failureReasonCode);
}
