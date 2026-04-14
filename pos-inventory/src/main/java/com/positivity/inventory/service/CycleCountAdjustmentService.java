package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.cyclecount.AdjustmentResponse;
import com.positivity.inventory.internal.dto.cyclecount.ApproveAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.CreateAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.RejectAdjustmentRequest;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service for managing cycle count adjustments.
 *
 * <p>
 * Implements the complete lifecycle:
 * <ol>
 * <li>Create adjustment and evaluate against thresholds</li>
 * <li>Auto-approve if below thresholds, or queue for manual approval</li>
 * <li>Handle approval/rejection by authorized users</li>
 * <li>Post to inventory ledger and update on-hand quantities</li>
 * </ol>
 */
public interface CycleCountAdjustmentService {

    /**
     * Creates a new cycle count adjustment and evaluates it against approval
     * thresholds.
     *
     * <p>
     * If the adjustment falls below all thresholds, it is automatically approved
     * and posted.
     * Otherwise, it enters PENDING_APPROVAL status.
     *
     * @param request the adjustment creation request
     * @return the created adjustment response
     */
    AdjustmentResponse createAdjustment(CreateAdjustmentRequest request);

    /**
     * Approves a pending adjustment.
     *
     * <p>
     * Only adjustments in PENDING_APPROVAL status can be approved.
     * After approval, the adjustment is posted to the inventory ledger.
     *
     * @param adjustmentId the adjustment ID
     * @param request      the approval request
     * @return the updated adjustment response
     */
    AdjustmentResponse approveAdjustment(
            @NonNull UUID adjustmentId, @NonNull ApproveAdjustmentRequest request, String correlationId);

    /**
     * Rejects a pending adjustment.
     *
     * <p>
     * Only adjustments in PENDING_APPROVAL status can be rejected.
     * Rejection is final - no ledger entry is created and on-hand is not changed.
     *
     * @param adjustmentId the adjustment ID
     * @param request      the rejection request
     * @return the updated adjustment response
     */
    AdjustmentResponse rejectAdjustment(UUID adjustmentId, RejectAdjustmentRequest request);

    /**
     * Retrieves a specific adjustment by ID.
     *
     * @param adjustmentId the adjustment ID
     * @return the adjustment response
     */
    AdjustmentResponse getAdjustment(UUID adjustmentId);

    /**
     * Lists all adjustments with a specific status.
     *
     * @param status the adjustment status
     * @return list of matching adjustments
     */
    List<AdjustmentResponse> listAdjustmentsByStatus(AdjustmentStatus status);

    /**
     * Gets the count of adjustments with a specific status.
     *
     * @param status the adjustment status
     * @return count of adjustments with the specified status
     */
    long countAdjustmentsByStatus(AdjustmentStatus status);
}
