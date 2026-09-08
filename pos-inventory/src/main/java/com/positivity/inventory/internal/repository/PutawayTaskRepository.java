package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PutawayTaskRepository extends JpaRepository<PutawayTask, UUID> {

    List<PutawayTask> findBySourceReceipt_ReceiptId(UUID sourceReceiptId);

    List<PutawayTask> findByStatusIn(List<PutawayTaskStatus> statuses);

    List<PutawayTask> findByStatusInAndSourceLocationId(List<PutawayTaskStatus> statuses, UUID sourceLocationId);

    /**
     * {@link #findByStatusIn} narrowed to the caller's reach (ADR-0061 §3, #1872): tasks whose
     * source is a reachable site, or a storage location replicated under one. Never called with an
     * empty set — an empty reach is an empty list decided before the query.
     */
    @Query("""
            SELECT t FROM PutawayTask t
            WHERE t.status IN :statuses
              AND (t.sourceLocationId IN :locationIds
                   OR t.sourceLocationId IN (SELECT s.storageLocationId FROM ExtStorageLocationReplica s
                                             WHERE s.siteId IN :locationIds))
            """)
    List<PutawayTask> findByStatusInWithinSourceLocations(
            @Param("statuses") List<PutawayTaskStatus> statuses, @Param("locationIds") Collection<UUID> locationIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PutawayTask t WHERE t.taskId = :taskId")
    Optional<PutawayTask> findByIdForUpdate(@Param("taskId") UUID taskId);

    boolean existsByProductIdAndSuggestedDestinationLocationIdAndStatusIn(
            UUID productId, UUID locationId, List<PutawayTaskStatus> statuses);

    /**
     * Most recently completed putaway task for a SKU that landed in a concrete
     * destination bin (odoo-parity K2, issue #1055 — the LAST_USED strategy).
     * Ordered by {@code updatedAt} descending so the freshest successful
     * putaway wins; {@code actualDestinationLocationId} is guaranteed non-null.
     */
    Optional<PutawayTask> findFirstByProductIdAndStatusAndActualDestinationLocationIdIsNotNullOrderByUpdatedAtDesc(
            UUID productId, PutawayTaskStatus status);
}
