package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.CycleCountAdjustment;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link CycleCountAdjustment} entities.
 */
public interface CycleCountAdjustmentRepository extends JpaRepository<CycleCountAdjustment, UUID> {

    /**
     * Find all adjustments with a specific status.
     *
     * @param status the adjustment status
     * @return list of matching adjustments
     */
    List<CycleCountAdjustment> findByStatus(AdjustmentStatus status);

    /**
     * Find all adjustments for a specific stock item.
     *
     * @param stockItemId the stock item ID
     * @return list of adjustments for that item
     */
    List<CycleCountAdjustment> findByStockItemId(UUID stockItemId);

    /**
     * Count adjustments with a specific status.
     *
     * @param status the adjustment status
     * @return count of matching adjustments
     */
    long countByStatus(AdjustmentStatus status);
}
