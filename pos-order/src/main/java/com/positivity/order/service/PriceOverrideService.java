package com.positivity.order.service;

import com.positivity.order.internal.dto.*;
import com.positivity.order.internal.entity.OverrideStatus;
import com.positivity.order.internal.entity.PriceOverride;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing price override operations.
 */
public interface PriceOverrideService {

        /**
         * Apply a price override to an order line.
         * 
         * @param request the override request details
         * @param userId  the ID of the user requesting the override (from auth token)
         * @return the override response with status
         */
        ApplyPriceOverrideResponse applyPriceOverride(ApplyPriceOverrideRequest request, String userId);

        /**
         * Approve a pending price override and return a view DTO.
         */
        PriceOverrideView approvePriceOverrideView(UUID overrideId, ApprovePriceOverrideRequest request,
                        UUID approverUserId, String approverRole);

        /**
         * Approve a pending price override.
         * 
         * @param overrideId     the ID of the override to approve
         * @param request        approval details
         * @param approverUserId the ID of the approving user
         * @param approverRole   the role of the approving user
         * @return the updated override
         */
        PriceOverride approvePriceOverride(UUID overrideId, ApprovePriceOverrideRequest request,
                        UUID approverUserId, String approverRole);

        /**
         * Reject a pending price override and return a view DTO.
         */
        PriceOverrideView rejectPriceOverrideView(UUID overrideId, RejectPriceOverrideRequest request,
                        UUID reviewerUserId, String approverRole);

        /**
         * Reject a pending price override.
         * 
         * @param overrideId     the ID of the override to reject
         * @param request        rejection details
         * @param reviewerUserId the ID of the rejecting user
         * @param reviewerRole   the role of the rejecting user
         * @return the updated override
         */
        PriceOverride rejectPriceOverride(UUID overrideId, RejectPriceOverrideRequest request,
                        UUID reviewerUserId, String approverRole);

        /**
         * Get a price override by ID as a view DTO.
         */
        PriceOverrideView getOverrideViewById(UUID overrideId);

        /**
         * Get a price override by ID.
         * 
         * @param overrideId the override ID
         * @return the override
         */
        PriceOverride getOverrideById(UUID overrideId);

        /**
         * Get all overrides for an order as view DTOs.
         */
        List<PriceOverrideView> getOverrideViewsByOrderId(UUID orderId);

        /**
         * Get all overrides for an order.
         * 
         * @param orderId the order ID
         * @return list of overrides
         */
        List<PriceOverride> getOverridesByOrderId(UUID orderId);

        /**
         * Get all pending approval overrides as view DTOs.
         */
        List<PriceOverrideView> getPendingApprovalViews();

        /**
         * Get all pending approval overrides.
         * 
         * @return list of pending overrides
         */
        List<PriceOverride> getPendingApprovals();

        /**
         * Get overrides created within a date range as view DTOs.
         */
        List<PriceOverrideView> getOverrideViewsByDateRange(Instant startDate, Instant endDate);

        /**
         * Get overrides created within a date range.
         * 
         * @param startDate start of date range
         * @param endDate   end of date range
         * @return list of overrides
         */
        List<PriceOverride> getOverridesByDateRange(Instant startDate, Instant endDate);

        /**
         * Get overrides by status as view DTOs.
         */
        List<PriceOverrideView> getOverrideViewsByStatus(String status);

        /**
         * Get overrides by status.
         * 
         * @param status the override status
         * @return list of overrides
         */
        List<PriceOverride> getOverridesByStatus(OverrideStatus status);
}
