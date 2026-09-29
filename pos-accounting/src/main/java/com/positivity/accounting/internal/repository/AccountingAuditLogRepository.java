package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingAuditLog;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for AccountingAuditLog entity (high-risk operation audit trail).
 */
public interface AccountingAuditLogRepository extends JpaRepository<AccountingAuditLog, UUID> {

    /**
     * Find audit rows for a specific entity, oldest first.
     */
    List<AccountingAuditLog> findByEntityTypeAndEntityIdOrderByTimestampAsc(String entityType, UUID entityId);

    /**
     * A bank reconciliation's stored trail (SPEC-manual-bank-reconciliation §4.9; story S5, #2304): its own rows
     * and those of its matches and outstanding items, in the pageable's order.
     */
    @Query("SELECT a FROM AccountingAuditLog a WHERE (a.entityType = :reconType AND a.entityId = :reconciliationId)"
            + " OR (a.entityType = :matchType AND a.entityId IN :matchIds)"
            + " OR (a.entityType = :itemType AND a.entityId IN :itemIds)")
    Page<AccountingAuditLog> findReconciliationTrail(
            @Param("reconType") String reconType,
            @Param("reconciliationId") UUID reconciliationId,
            @Param("matchType") String matchType,
            @Param("matchIds") Collection<UUID> matchIds,
            @Param("itemType") String itemType,
            @Param("itemIds") Collection<UUID> itemIds,
            Pageable pageable);

    /** The latest audit row of an operation (bank reconciliation policy {@code updatedAt}/{@code updatedBy}, #2305). */
    Optional<AccountingAuditLog> findFirstByOperationOrderByTimestampDesc(@NonNull String operation);
}
