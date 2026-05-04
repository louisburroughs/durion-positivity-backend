package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Repository for AccountingEvent entity.
 * Supports CRUD, status queries, and organization-based pagination.
 */
@Repository
public interface AccountingEventRepository
        extends JpaRepository<AccountingEvent, UUID>, JpaSpecificationExecutor<AccountingEvent> {

    /**
     * Find accounting events by organization with pagination.
     */
    Page<AccountingEvent> findByOrganizationId(UUID organizationId, Pageable pageable);

    /**
     * Find accounting events by organization and status with pagination.
     */
    Page<AccountingEvent> findByOrganizationIdAndStatus(
            UUID organizationId, AccountingEventStatus status, Pageable pageable);

    /**
     * Find accounting events by source system.
     * Uses indexed column for efficient querying.
     */
    Page<AccountingEvent> findBySourceSystem(String sourceSystem, Pageable pageable);
}
