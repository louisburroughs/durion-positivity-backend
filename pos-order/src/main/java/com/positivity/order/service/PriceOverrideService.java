package com.positivity.order.service;

import com.positivity.order.internal.dto.*;
import com.positivity.order.internal.model.OverrideStatus;
import com.positivity.order.internal.model.PriceOverride;

import java.time.Instant;
import java.util.List;

/**
 * Service for managing price override operations.
 */
public interface PriceOverrideService {
    
    /**
     * Apply a price override to an order line.
     * 
     * @param request the override request details
     * @param userId the ID of the user requesting the override
     * @return the override response with status
     */
    ApplyPriceOverrideResponse applyPriceOverride(ApplyPriceOverrideRequest request, String userId);
    
    /**
     * Approve a pending price override.
     * 
     * @param overrideId the ID of the override to approve
     * @param request approval details
     * @param approverUserId the ID of the approving user
     * @param approverRole the role of the approving user
     * @return the updated override
     */
    PriceOverride approvePriceOverride(Long overrideId, ApprovePriceOverrideRequest request, 
                                       String approverUserId, String approverRole);
    
    /**
     * Reject a pending price override.
     * 
     * @param overrideId the ID of the override to reject
     * @param request rejection details
     * @param reviewerUserId the ID of the rejecting user
     * @param reviewerRole the role of the rejecting user
     * @return the updated override
     */
    PriceOverride rejectPriceOverride(Long overrideId, RejectPriceOverrideRequest request,
                                      String reviewerUserId, String reviewerRole);
    
    /**
     * Get a price override by ID.
     * 
     * @param overrideId the override ID
     * @return the override
     */
    PriceOverride getOverrideById(Long overrideId);
    
    /**
     * Get all overrides for an order.
     * 
     * @param orderId the order ID
     * @return list of overrides
     */
    List<PriceOverride> getOverridesByOrderId(String orderId);
    
    /**
     * Get all pending approval overrides.
     * 
     * @return list of pending overrides
     */
    List<PriceOverride> getPendingApprovals();
    
    /**
     * Get overrides created within a date range.
     * 
     * @param startDate start of date range
     * @param endDate end of date range
     * @return list of overrides
     */
    List<PriceOverride> getOverridesByDateRange(Instant startDate, Instant endDate);
    
    /**
     * Get overrides by status.
     * 
     * @param status the override status
     * @return list of overrides
     */
    List<PriceOverride> getOverridesByStatus(OverrideStatus status);
}
