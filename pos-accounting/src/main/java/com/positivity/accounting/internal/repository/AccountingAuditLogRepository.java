package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingAuditLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for AccountingAuditLog entity (high-risk operation audit trail).
 */
public interface AccountingAuditLogRepository extends JpaRepository<AccountingAuditLog, UUID> {

    /**
     * Find audit rows for a specific entity, oldest first.
     */
    List<AccountingAuditLog> findByEntityTypeAndEntityIdOrderByTimestampAsc(String entityType, UUID entityId);
}
