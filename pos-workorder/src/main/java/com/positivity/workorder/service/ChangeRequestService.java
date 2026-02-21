package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.CreateChangeRequestDTO;
import com.positivity.workorder.internal.entity.*;
import com.positivity.workorder.internal.entity.ChangeRequest.ChangeRequestStatus;
import com.positivity.workorder.internal.repository.*;
import com.positivity.security.common.SecurityContextHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChangeRequestService {
    private static final String SYSTEM_ACTOR = "system";
    private final ChangeRequestRepository changeRequestRepository;
    private final WorkorderRepository workOrderRepository;
    private final WorkorderServiceRepository workOrderServiceRepository;
    private final WorkorderPartRepository workOrderPartRepository;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final IdempotencyService idempotencyService;

    /**
     * Create a new change request with associated work order items.
     * Items are marked as PENDING_APPROVAL until approved.
     */
    @Transactional
    public ChangeRequest createChangeRequest(CreateChangeRequestDTO dto) {
        // Validation
        if (dto.getDescription() == null || dto.getDescription().trim().isEmpty()) {
            throw new IllegalArgumentException("Description is required for change request");
        }

        boolean hasItems = (dto.getServices() != null && !dto.getServices().isEmpty())
                || (dto.getParts() != null && !dto.getParts().isEmpty());
        if (!hasItems) {
            throw new IllegalArgumentException("At least one service or part item is required for change request");
        }

        // Validate work order exists and is in progress
        Workorder workOrder = workOrderRepository.findById(dto.getWorkorderId())
                .orElseThrow(() -> new IllegalArgumentException("Work order not found: " + dto.getWorkorderId()));

        if (workOrder.getStatus() != WorkorderStatus.WORK_IN_PROGRESS) {
            throw new IllegalStateException(
                    "Work order must be in WORK_IN_PROGRESS status to create change request. Current status: "
                            + workOrder.getStatus());
        }

        // Emergency/Safety validation
        if (Boolean.TRUE.equals(dto.getIsEmergencyException())) {
            validateEmergencyDocumentation(dto);
        }

        // Create change request
        ChangeRequest changeRequest = ChangeRequest.builder()
                .workorderId(dto.getWorkorderId())
                .requestedByUserId(dto.getRequestedByUserId())
                .description(dto.getDescription())
                .isEmergencyException(Boolean.TRUE.equals(dto.getIsEmergencyException()))
                .exceptionReason(dto.getExceptionReason())
                .status(ChangeRequestStatus.AWAITING_ADVISOR_REVIEW)
                .requestedAt(LocalDateTime.now())
                .build();

        changeRequest = changeRequestRepository.save(changeRequest);
        log.info("Created change request {} for work order {}", changeRequest.getId(), dto.getWorkorderId());

        // Create associated work order services
        if (dto.getServices() != null) {
            for (CreateChangeRequestDTO.WorkorderItemDTO serviceDto : dto.getServices()) {
                com.positivity.workorder.internal.entity.WorkorderService service = createWorkorderServiceEntity(
                        workOrder, changeRequest, serviceDto);
                workOrderServiceRepository.save(service);
            }
        }

        // Create associated work order parts
        if (dto.getParts() != null) {
            for (CreateChangeRequestDTO.WorkorderItemDTO partDto : dto.getParts()) {
                WorkorderPart part = createWorkorderPart(workOrder, changeRequest, partDto);
                workOrderPartRepository.save(part);
            }
        }

        // TODO: Generate supplemental PDF estimate
        // changeRequest.setSupplementalEstimatePdfId(generateSupplementalEstimate(changeRequest));
        // changeRequestRepository.save(changeRequest);

        return changeRequest;
    }

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
    @Transactional
    public ChangeRequest createChangeRequestWithIdempotency(CreateChangeRequestDTO dto, String idempotencyKey) {
        // Check for existing change request if idempotency key is provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingChangeRequestId = idempotencyService.getExistingChangeRequestId(idempotencyKey);
            if (existingChangeRequestId.isPresent()) {
                log.info("Idempotent request detected for key {}; returning existing change request {}",
                        idempotencyKey, existingChangeRequestId.get());
                return changeRequestRepository.findById(existingChangeRequestId.get())
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotency key points to non-existent change request: "
                                        + existingChangeRequestId.get()));
            }
        }

        // Create new change request
        ChangeRequest created = createChangeRequest(dto);

        // Register idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                idempotencyService.markKeyProcessedForChangeRequest(idempotencyKey, created.getId());
                // Force flush so any unique-constraint violation is raised within this
                // try/catch
                TransactionAspectSupport.currentTransactionStatus().flush();
            } catch (DataIntegrityViolationException e) {
                // Race condition: another request already registered this key
                // The unique constraint violation means another transaction has committed the
                // key.
                // Mark our transaction for rollback to prevent persisting the duplicate change
                // request.
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

                // Retrieve the existing change request that won the race
                // Note: The DataIntegrityViolationException indicates the other transaction has
                // committed, so the change request should be visible with READ_COMMITTED
                // isolation.
                Optional<UUID> existingChangeRequestId = idempotencyService.getExistingChangeRequestId(idempotencyKey);
                if (existingChangeRequestId.isPresent()) {
                    log.warn(
                            "Race condition detected: idempotency key {} already registered for change request {}, returning existing change request",
                            idempotencyKey, existingChangeRequestId.get());
                    // Return the existing change request to maintain idempotency semantics
                    // The transaction will be rolled back, preventing the duplicate 'created'
                    // change request
                    return changeRequestRepository.findById(existingChangeRequestId.get())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Idempotency key " + idempotencyKey + " points to non-existent change request: "
                                            + existingChangeRequestId.get()));
                } else {
                    // This should not happen - if DataIntegrityViolationException was thrown,
                    // the key must exist. This indicates a serious data inconsistency.
                    log.error("Race condition detected but no existing change request found for idempotency key {}",
                            idempotencyKey);
                    throw new IllegalStateException(
                            "Idempotency key collision but no existing change request found: " + idempotencyKey, e);
                }
            }
        }

        return created;
    }

    /**
     * Approve a change request and move items to OPEN/READY_TO_EXECUTE status
     */
    @Transactional
    public ChangeRequest approveChangeRequest(UUID changeRequestId, UUID approvedBy, String approvalNote) {
        String resolvedActorId = SecurityContextHelper.getCurrentUserIdOrDefault(SYSTEM_ACTOR);
        ChangeRequest changeRequest = changeRequestRepository.findById(changeRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Change request not found: " + changeRequestId));

        if (!changeRequest.canApprove()) {
            throw new IllegalStateException(
                    "Change request cannot be approved in current state: " + changeRequest.getStatus());
        }

        // Approval note is required as the approval artifact
        if (approvalNote == null || approvalNote.trim().isEmpty()) {
            throw new IllegalArgumentException("Approval note is required as the approval artifact");
        }

        changeRequest.setStatus(ChangeRequestStatus.APPROVED);
        changeRequest.setApprovedAt(LocalDateTime.now());
        changeRequest.setApprovedBy(approvedBy);
        changeRequest.setApprovalNote(approvalNote);

        changeRequest = changeRequestRepository.save(changeRequest);

        // Create immutable ApprovalRecord for audit trail
        ApprovalRecord approvalRecord = ApprovalRecord.builder()
                .changeRequestId(changeRequestId)
                .workorderId(changeRequest.getWorkorderId())
                .resolutionStatus(ApprovalRecord.ResolutionStatus.APPROVED)
                .resolvedAt(LocalDateTime.now())
                .resolvedBy(resolvedActorId)
                .approvalNote(approvalNote)
                .build();
        approvalRecordRepository.save(approvalRecord);

        // Move associated items from PENDING_APPROVAL to OPEN/READY_TO_EXECUTE
        updateItemsStatus(changeRequest.getId(), WorkorderItemStatus.READY_TO_EXECUTE);

        log.info("Approved change request {} by user {}", changeRequestId, approvedBy);
        return changeRequest;
    }

    /**
     * Decline a change request and move items to CANCELLED status
     */
    @Transactional
    public ChangeRequest declineChangeRequest(UUID changeRequestId, String approvalNote) {
        String resolvedActorId = SecurityContextHelper.getCurrentUserIdOrDefault(SYSTEM_ACTOR);
        ChangeRequest changeRequest = changeRequestRepository.findById(changeRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Change request not found: " + changeRequestId));

        if (!changeRequest.canDecline()) {
            throw new IllegalStateException(
                    "Change request cannot be declined in current state: " + changeRequest.getStatus());
        }

        // Approval note is required as the approval artifact (records the decision)
        if (approvalNote == null || approvalNote.trim().isEmpty()) {
            throw new IllegalArgumentException("Approval note is required to record the decline decision");
        }

        changeRequest.setStatus(ChangeRequestStatus.DECLINED);
        changeRequest.setDeclinedAt(LocalDateTime.now());
        changeRequest.setApprovalNote(approvalNote);

        changeRequest = changeRequestRepository.save(changeRequest);

        // Create immutable ApprovalRecord for audit trail
        ApprovalRecord approvalRecord = ApprovalRecord.builder()
                .changeRequestId(changeRequestId)
                .workorderId(changeRequest.getWorkorderId())
                .resolutionStatus(ApprovalRecord.ResolutionStatus.REJECTED)
                .resolvedAt(LocalDateTime.now())
                .resolvedBy(resolvedActorId)
                .approvalNote(approvalNote)
                .build();
        approvalRecordRepository.save(approvalRecord);

        // Move associated items from PENDING_APPROVAL to CANCELLED (not billable)
        updateItemsStatus(changeRequest.getId(), WorkorderItemStatus.CANCELLED);

        log.info("Declined change request {}", changeRequestId);
        return changeRequest;
    }

    /**
     * Apply emergency override to approve a change request with exception.
     * Restricted to users with Manager role or equivalent permission.
     */
    @Transactional
    public ChangeRequest applyEmergencyOverride(UUID changeRequestId, UUID managerId, String exceptionReason) {
        String resolvedActorId = SecurityContextHelper.getCurrentUserIdOrDefault(SYSTEM_ACTOR);
        ChangeRequest changeRequest = changeRequestRepository.findById(changeRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Change request not found: " + changeRequestId));

        if (!changeRequest.canApprove()) {
            throw new IllegalStateException(
                    "Change request cannot be approved in current state: " + changeRequest.getStatus());
        }

        // Exception reason is required for emergency override
        if (exceptionReason == null || exceptionReason.trim().isEmpty()) {
            throw new IllegalArgumentException("Exception reason is required for emergency override");
        }

        changeRequest.setStatus(ChangeRequestStatus.APPROVED_WITH_EXCEPTION);
        changeRequest.setApprovedAt(LocalDateTime.now());
        changeRequest.setApprovedBy(managerId);
        changeRequest.setIsEmergencyException(true);
        changeRequest.setExceptionReason(exceptionReason);

        changeRequest = changeRequestRepository.save(changeRequest);

        // Create immutable ApprovalRecord for audit trail with exception flag
        ApprovalRecord approvalRecord = ApprovalRecord.builder()
                .changeRequestId(changeRequestId)
                .workorderId(changeRequest.getWorkorderId())
                .resolutionStatus(ApprovalRecord.ResolutionStatus.APPROVED_WITH_EXCEPTION)
                .resolvedAt(LocalDateTime.now())
                .resolvedBy(resolvedActorId)
                .exceptionReason(exceptionReason)
                .build();
        approvalRecordRepository.save(approvalRecord);

        // Move associated items from PENDING_APPROVAL to READY_TO_EXECUTE
        updateItemsStatus(changeRequest.getId(), WorkorderItemStatus.READY_TO_EXECUTE);

        log.info("Applied emergency override to change request {} by manager {}", changeRequestId, managerId);
        return changeRequest;
    }

    /**
     * Record customer denial acknowledgment for emergency/safety items
     */
    @Transactional
    public void recordCustomerDenialAcknowledgment(UUID changeRequestId) {
        ChangeRequest changeRequest = changeRequestRepository.findById(changeRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Change request not found: " + changeRequestId));

        if (!changeRequest.getIsEmergencyException()) {
            throw new IllegalStateException("Change request is not marked as emergency/safety exception");
        }

        if (changeRequest.getStatus() != ChangeRequestStatus.DECLINED) {
            throw new IllegalStateException("Change request must be declined to record customer denial acknowledgment");
        }

        // Mark all emergency items as acknowledged
        List<com.positivity.workorder.internal.entity.WorkorderService> services = workOrderServiceRepository
                .findByChangeRequestId(changeRequestId);
        for (com.positivity.workorder.internal.entity.WorkorderService service : services) {
            if (Boolean.TRUE.equals(service.getIsEmergencySafety())) {
                service.setCustomerDenialAcknowledged(true);
                workOrderServiceRepository.save(service);
            }
        }

        List<WorkorderPart> parts = workOrderPartRepository.findByChangeRequestId(changeRequestId);
        for (WorkorderPart part : parts) {
            if (Boolean.TRUE.equals(part.getIsEmergencySafety())) {
                part.setCustomerDenialAcknowledged(true);
                workOrderPartRepository.save(part);
            }
        }

        log.info("Recorded customer denial acknowledgment for emergency items in change request {}", changeRequestId);
    }

    /**
     * Check if work order can be closed (all emergency items must be acknowledged
     * if declined)
     */
    public boolean canCloseWorkorder(UUID workorderId) {
        List<ChangeRequest> emergencyRequests = changeRequestRepository.findByWorkorderIdAndStatus(
                workorderId, ChangeRequestStatus.DECLINED);

        for (ChangeRequest request : emergencyRequests) {
            if (Boolean.TRUE.equals(request.getIsEmergencyException())) {
                // Check if all emergency items are acknowledged
                List<com.positivity.workorder.internal.entity.WorkorderService> services = workOrderServiceRepository
                        .findByChangeRequestId(request.getId());
                for (com.positivity.workorder.internal.entity.WorkorderService service : services) {
                    if (Boolean.TRUE.equals(service.getIsEmergencySafety())
                            && !Boolean.TRUE.equals(service.getCustomerDenialAcknowledged())) {
                        return false;
                    }
                }

                List<WorkorderPart> parts = workOrderPartRepository.findByChangeRequestId(request.getId());
                for (WorkorderPart part : parts) {
                    if (Boolean.TRUE.equals(part.getIsEmergencySafety())
                            && !Boolean.TRUE.equals(part.getCustomerDenialAcknowledged())) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    /**
     * Check if work order has any pending approval-gated change requests.
     * Returns true if there are unresolved change requests blocking completion.
     */
    public boolean hasPendingApprovalGatedRequests(UUID workorderId) {
        List<ChangeRequest> pendingRequests = changeRequestRepository.findByWorkorderIdAndStatus(
                workorderId, ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);

        // Filter for approval-gated requests (all are by default, but check field)
        return pendingRequests.stream()
                .anyMatch(request -> Boolean.TRUE.equals(request.getIsApprovalGated()));
    }

    /**
     * Get all pending approval-gated change requests for a work order.
     */
    public List<ChangeRequest> getPendingApprovalGatedRequests(UUID workorderId) {
        List<ChangeRequest> pendingRequests = changeRequestRepository.findByWorkorderIdAndStatus(
                workorderId, ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);

        // Filter for approval-gated requests
        return pendingRequests.stream()
                .filter(request -> Boolean.TRUE.equals(request.getIsApprovalGated()))
                .toList();
    }

    public List<ChangeRequest> getChangeRequestsByWorkorder(UUID workorderId) {
        return changeRequestRepository.findByWorkorderId(workorderId);
    }

    public ChangeRequest getChangeRequestById(UUID id) {
        return changeRequestRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Change request not found: " + id));
    }

    private void validateEmergencyDocumentation(CreateChangeRequestDTO dto) {
        boolean hasEmergencyItems = false;

        if (dto.getServices() != null) {
            for (CreateChangeRequestDTO.WorkorderItemDTO service : dto.getServices()) {
                if (Boolean.TRUE.equals(service.getIsEmergencySafety())) {
                    hasEmergencyItems = true;
                    validateEmergencyItem(service);
                }
            }
        }

        if (dto.getParts() != null) {
            for (CreateChangeRequestDTO.WorkorderItemDTO part : dto.getParts()) {
                if (Boolean.TRUE.equals(part.getIsEmergencySafety())) {
                    hasEmergencyItems = true;
                    validateEmergencyItem(part);
                }
            }
        }

        if (!hasEmergencyItems) {
            throw new IllegalArgumentException(
                    "Emergency exception flagged but no items are marked as emergency/safety");
        }
    }

    private void validateEmergencyItem(CreateChangeRequestDTO.WorkorderItemDTO item) {
        // Photo evidence OR explicit "photo not possible" + notes required
        boolean hasPhoto = item.getPhotoEvidenceUrl() != null && !item.getPhotoEvidenceUrl().trim().isEmpty();
        boolean photoNotPossible = Boolean.TRUE.equals(item.getPhotoNotPossible());
        boolean hasNotes = item.getEmergencyNotes() != null && !item.getEmergencyNotes().trim().isEmpty();

        if (!hasPhoto && !photoNotPossible) {
            throw new IllegalArgumentException(
                    "Emergency/safety items require photo evidence or must mark 'photo not possible'");
        }

        if (!hasPhoto && !hasNotes) {
            throw new IllegalArgumentException(
                    "Emergency/safety items without photo must have notes explaining the situation");
        }
    }

    private com.positivity.workorder.internal.entity.WorkorderService createWorkorderServiceEntity(
            Workorder workOrder, ChangeRequest changeRequest,
            CreateChangeRequestDTO.WorkorderItemDTO dto) {
        return com.positivity.workorder.internal.entity.WorkorderService.builder()
                .workOrder(workOrder)
                .serviceEntityId(dto.getServiceEntityId())
                .status(WorkorderItemStatus.PENDING_APPROVAL)
                .changeRequestId(changeRequest.getId())
                .isEmergencySafety(Boolean.TRUE.equals(dto.getIsEmergencySafety()))
                .photoEvidenceUrl(dto.getPhotoEvidenceUrl())
                .emergencyNotes(dto.getEmergencyNotes())
                .photoNotPossible(Boolean.TRUE.equals(dto.getPhotoNotPossible()))
                .build();
    }

    private WorkorderPart createWorkorderPart(Workorder workOrder, ChangeRequest changeRequest,
            CreateChangeRequestDTO.WorkorderItemDTO dto) {
        return WorkorderPart.builder()
                .workorder(workOrder)
                .productEntityId(dto.getProductEntityId())
                .nonInventoryProductEntityId(dto.getNonInventoryProductEntityId())
                // Convert Integer quantity to BigDecimal for WorkorderPart entity
                .quantity(dto.getQuantity() != null ? java.math.BigDecimal.valueOf(dto.getQuantity()) : null)
                .status(WorkorderItemStatus.PENDING_APPROVAL)
                .changeRequestId(changeRequest.getId())
                .isEmergencySafety(Boolean.TRUE.equals(dto.getIsEmergencySafety()))
                .photoEvidenceUrl(dto.getPhotoEvidenceUrl())
                .emergencyNotes(dto.getEmergencyNotes())
                .photoNotPossible(Boolean.TRUE.equals(dto.getPhotoNotPossible()))
                .build();
    }

    private void updateItemsStatus(UUID changeRequestId, WorkorderItemStatus newStatus) {
        // Update services
        List<com.positivity.workorder.internal.entity.WorkorderService> services = workOrderServiceRepository
                .findByChangeRequestId(changeRequestId);
        for (com.positivity.workorder.internal.entity.WorkorderService service : services) {
            service.setStatus(newStatus);
            workOrderServiceRepository.save(service);
        }

        // Update parts
        List<WorkorderPart> parts = workOrderPartRepository.findByChangeRequestId(changeRequestId);
        for (WorkorderPart part : parts) {
            part.setStatus(newStatus);
            workOrderPartRepository.save(part);
        }
    }
}
