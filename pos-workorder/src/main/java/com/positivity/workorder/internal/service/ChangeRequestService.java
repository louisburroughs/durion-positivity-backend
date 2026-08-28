package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.ChangeRequestResponse;
import com.positivity.workorder.internal.dto.CreateChangeRequestDTO;
import java.util.List;
import java.util.UUID;

public interface ChangeRequestService {

    /**
     * Create a new change request with associated work order items.
     * Items are marked as PENDING_APPROVAL until approved.
     */
    ChangeRequestResponse createChangeRequest(CreateChangeRequestDTO dto);

    /**
     * Create a change request with idempotency key support.
     *
     * <p>
     * If an idempotency key is provided and has been processed before,
     * returns the existing change request instead of creating a duplicate.
     * </p>
     *
     * @param dto            the change request creation request
     * @param idempotencyKey optional idempotency key for duplicate prevention; if
     *                       null, idempotency is not enforced
     * @return the created or existing change request
     */
    ChangeRequestResponse createChangeRequestWithIdempotency(CreateChangeRequestDTO dto, String idempotencyKey);

    /**
     * Approve a change request and move items to OPEN/READY_TO_EXECUTE status
     */
    ChangeRequestResponse approveChangeRequest(UUID changeRequestId, UUID approvedBy, String approvalNote);

    /**
     * Decline a change request and move items to CANCELLED status
     */
    ChangeRequestResponse declineChangeRequest(UUID changeRequestId, String approvalNote);

    /**
     * Apply emergency override to approve a change request with exception.
     * Restricted to users with Manager role or equivalent permission.
     */
    ChangeRequestResponse applyEmergencyOverride(UUID changeRequestId, String exceptionReason);

    /**
     * Record customer denial acknowledgment for emergency/safety items
     */
    void recordCustomerDenialAcknowledgment(UUID changeRequestId);

    /**
     * Check if work order can be closed (all emergency items must be acknowledged
     * if declined)
     */
    boolean canCloseWorkorder(UUID workorderId);

    /**
     * Check if work order has any pending approval-gated change requests.
     * Returns true if there are unresolved change requests blocking completion.
     */
    boolean hasPendingApprovalGatedRequests(UUID workorderId);

    /**
     * Get all pending approval-gated change requests for a work order.
     */
    List<ChangeRequestResponse> getPendingApprovalGatedRequests(UUID workorderId);

    List<ChangeRequestResponse> getChangeRequestsByWorkorder(UUID workorderId);

    ChangeRequestResponse getChangeRequestById(UUID id);
}
