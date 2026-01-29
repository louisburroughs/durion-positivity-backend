package com.positivity.inventory.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.inventory.internal.model.cyclecount.AdjustmentStatus;
import com.positivity.inventory.internal.model.cyclecount.CycleCountAdjustment;

import java.util.List;

/**
 * Repository for {@link CycleCountAdjustment} entities.
 */
@Repository
public interface CycleCountAdjustmentRepository extends JpaRepository<CycleCountAdjustment, Long> {
    
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
    List<CycleCountAdjustment> findByStockItemId(String stockItemId);
    
    /**
     * Count adjustments with a specific status.
     * 
     * @param status the adjustment status
     * @return count of matching adjustments
     */
    long countByStatus(AdjustmentStatus status);
}
