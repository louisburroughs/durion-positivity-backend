package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingStatusSyncAudit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for append-only accounting status sync audit records.
 *
 * <p>
 * Only {@code save()} and read operations are expected. No delete or update
 * operations are performed on this table.
 * </p>
 */
public interface AccountingStatusSyncAuditRepository extends JpaRepository<AccountingStatusSyncAudit, UUID> {
}
