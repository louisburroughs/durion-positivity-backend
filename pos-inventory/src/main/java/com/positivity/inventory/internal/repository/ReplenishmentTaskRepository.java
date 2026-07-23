package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ReplenishmentTask;
import com.positivity.inventory.internal.enums.ReplenishmentStatus;
import com.positivity.inventory.internal.enums.ReplenishmentTriggerType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplenishmentTaskRepository extends JpaRepository<ReplenishmentTask, UUID> {

    List<ReplenishmentTask> findByStatus(ReplenishmentStatus status);

    List<ReplenishmentTask> findByStatusIn(List<ReplenishmentStatus> statuses);

    boolean existsByItemSKUAndDestinationLocationIdAndStatusIn(
            String itemSKU, UUID destinationLocationId, List<ReplenishmentStatus> statuses);

    /** Open task lookup used by the batch scan to refresh instead of duplicate (odoo-parity F1). */
    Optional<ReplenishmentTask> findFirstByItemSKUAndDestinationLocationIdAndStatusInOrderByCreatedAtAsc(
            String itemSKU, UUID destinationLocationId, List<ReplenishmentStatus> statuses);

    /**
     * Open tasks for one SKU/destination — the in-progress supply netted out of the
     * forecast-aware replenish quantity (odoo-parity F2, issue #1040).
     */
    List<ReplenishmentTask> findByItemSKUAndDestinationLocationIdAndStatusIn(
            String itemSKU, UUID destinationLocationId, List<ReplenishmentStatus> statuses);

    /**
     * Per-(policy, day) idempotency guard for the batch scan (odoo-parity F1):
     * true when a batch-triggered task for this SKU/destination was already
     * created on or after the given day boundary, regardless of its status.
     */
    boolean existsByItemSKUAndDestinationLocationIdAndTriggerTypeAndCreatedAtGreaterThanEqual(
            String itemSKU, UUID destinationLocationId, ReplenishmentTriggerType triggerType, Instant createdAtFrom);
}
