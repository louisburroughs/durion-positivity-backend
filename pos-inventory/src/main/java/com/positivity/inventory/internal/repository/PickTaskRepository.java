package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.PickListEntity;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PickTaskRepository extends JpaRepository<PickTaskEntity, UUID> {

    List<PickTaskEntity> findByPickListOrderBySortOrderAsc(PickListEntity pickList);

    List<PickTaskEntity> findByPickList_PickListId(UUID pickListId);

    /**
     * Released-not-picked pick-task remainders for one SKU (odoo-parity A2, issue #1028): sum
     * of {@code quantityRequired - quantityPicked} (per task, floored at zero) over tasks in
     * the given task status whose owning pick list is in one of the given (released) statuses.
     *
     * <p>SKU matching covers both identity columns: the free-text {@code sku} and, when the
     * caller's stock-item id parses as a UUID, {@code productId} (ledger stock-item ids are
     * product UUIDs rendered as strings). Site scoping matches {@code suggestedLocationId}
     * against the site itself or any storage location replicated under that site
     * ({@code ext_storage_location.site_id}); tasks with no suggested location only count in
     * unscoped queries.
     */
    @Query("""
                        SELECT COALESCE(SUM(CASE WHEN t.quantityRequired - t.quantityPicked > 0
                                                 THEN t.quantityRequired - t.quantityPicked
                                                 ELSE 0 END), 0)
                        FROM PickTaskEntity t
                        WHERE (t.sku = :stockItemId OR (:productId IS NOT NULL AND t.productId = :productId))
                          AND t.status = :taskStatus
                          AND t.pickList.status IN :listStatuses
                          AND (:siteId IS NULL
                               OR t.suggestedLocationId = :siteId
                               OR t.suggestedLocationId IN (SELECT s.storageLocationId
                                                            FROM ExtStorageLocationReplica s
                                                            WHERE s.siteId = :siteId))
                        """)
    Long sumReleasedUnpickedRemainderForSku(
            @Param("stockItemId") String stockItemId,
            @Param("productId") UUID productId,
            @Param("taskStatus") PickTaskStatus taskStatus,
            @Param("listStatuses") Collection<PickListStatus> listStatuses,
            @Param("siteId") UUID siteId);
}
