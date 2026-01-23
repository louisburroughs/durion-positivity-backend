package com.positivity.workorder.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published when an Estimate's financial total changes,
 * potentially requiring re-approval of associated Work Orders.
 * 
 * This event enables event-driven communication between the
 * Pricing domain (Estimate) and WorkExec domain (Workorder).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EstimateRevisedEvent {
    /**
     * ID of the Estimate that was revised
     */
    private Long estimateId;
    
    /**
     * ID of the Workorder associated with this Estimate (if any)
     */
    private Long workorderId;
    
    /**
     * Previous total amount before revision
     */
    private BigDecimal oldTotal;
    
    /**
     * New total amount after revision
     */
    private BigDecimal newTotal;
    
    /**
     * ID of the user who made the change
     */
    private Long changedBy;
    
    /**
     * Timestamp when the revision occurred
     */
    private Instant timestamp;
    
    /**
     * Event type identifier for routing and logging
     */
    public String getEventType() {
        return "estimate.revised.v1";
    }
    
    /**
     * Calculate the amount of change (new - old)
     */
    public BigDecimal getChangeAmount() {
        if (oldTotal == null || newTotal == null) {
            return BigDecimal.ZERO;
        }
        return newTotal.subtract(oldTotal);
    }
    
    /**
     * Check if this represents an increase in price
     */
    public boolean isIncrease() {
        return getChangeAmount().compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * Check if this represents a decrease in price
     */
    public boolean isDecrease() {
        return getChangeAmount().compareTo(BigDecimal.ZERO) < 0;
    }
}
