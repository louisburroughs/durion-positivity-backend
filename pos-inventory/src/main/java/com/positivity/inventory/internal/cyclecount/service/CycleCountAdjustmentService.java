package com.positivity.inventory.internal.cyclecount.service;

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
     * <p>
     * Location scope (ADR-0061, #2151): gated on the location the adjustment concerns — its stored
     * {@code locationId}, else (a legacy row from before #2167) the linked task's bin when it holds
     * a location UUID, the same resolution the ledger posting uses — after the not-found lookup and
     * before any state change, against {@code inventory:adjustment:approve}. Only a row with
     * neither is <strong>denied</strong> to a caller whose grant is location-scoped (fail closed);
     * a globally granted caller is unaffected.
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
     * <p>
     * Location scope (ADR-0061, #2151): gated on the location the adjustment concerns — its stored
     * {@code locationId}, else (a legacy row from before #2167) the linked task's bin when it holds
     * a location UUID, the same resolution the ledger posting uses — after the not-found lookup and
     * before any state change, against {@code inventory:adjustment:approve}. Only a row with
     * neither is <strong>denied</strong> to a caller whose grant is location-scoped (fail closed);
     * a globally granted caller is unaffected.
     *
     * @param adjustmentId the adjustment ID
     * @param request      the rejection request
     * @return the updated adjustment response
     */
    AdjustmentResponse rejectAdjustment(UUID adjustmentId, RejectAdjustmentRequest request);

    /**
     * Retrieves a specific adjustment by ID.
     *
     * <p>
     * Location scope (ADR-0061, #2151): gated on the location the adjustment concerns — its stored
     * {@code locationId}, else (a legacy row from before #2167) the linked task's bin when it holds
     * a location UUID — after the not-found lookup, against {@code inventory:adjustment:view} or
     * {@code inventory:adjustment:approve}. Only a row with neither is <strong>denied</strong> to a
     * caller whose grant is location-scoped (fail closed); a globally granted caller is unaffected.
     *
     * @param adjustmentId the adjustment ID
     * @return the adjustment response
     */
    AdjustmentResponse getAdjustment(UUID adjustmentId);

    /**
     * Lists all adjustments with a specific status.
     *
     * <p>
     * Narrowed to the caller's location reach (ADR-0061, #2151) against {@code inventory:adjustment:view}
     * or {@code inventory:adjustment:approve}, on the stored {@code locationId} column alone: a
     * location-scoped caller sees only adjustments whose column names a reachable site, so legacy
     * rows with no {@code locationId} are excluded for them even when the by-id reads would admit
     * the row through its task's bin; a globally granted caller sees every row.
     *
     * @param status the adjustment status
     * @return list of matching adjustments
     */
    List<AdjustmentResponse> listAdjustmentsByStatus(AdjustmentStatus status);

    /**
     * Gets the count of adjustments with a specific status, narrowed to the caller's reach exactly
     * like {@link #listAdjustmentsByStatus}.
     *
     * @param status the adjustment status
     * @return count of adjustments with the specified status
     */
    long countAdjustmentsByStatus(AdjustmentStatus status);
}
