package com.positivity.workorder.service;

import com.positivity.workorder.dto.CreateChangeRequestDTO;
import com.positivity.workorder.entity.*;
import com.positivity.workorder.entity.ChangeRequest.ChangeRequestStatus;
import com.positivity.workorder.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChangeRequestService {
    private final ChangeRequestRepository changeRequestRepository;
    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderServiceRepository workOrderServiceRepository;
    private final WorkOrderPartRepository workOrderPartRepository;

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
        WorkOrder workOrder = workOrderRepository.findById(dto.getWorkOrderId())
                .orElseThrow(() -> new IllegalArgumentException("Work order not found: " + dto.getWorkOrderId()));
        
        if (workOrder.getStatus() != WorkOrderStatus.IN_PROGRESS) {
            throw new IllegalStateException("Work order must be in IN_PROGRESS status to create change request. Current status: " + workOrder.getStatus());
        }

        // Emergency/Safety validation
        if (Boolean.TRUE.equals(dto.getIsEmergencyException())) {
            validateEmergencyDocumentation(dto);
        }

        // Create change request
        ChangeRequest changeRequest = ChangeRequest.builder()
                .workOrderId(dto.getWorkOrderId())
                .requestedByUserId(dto.getRequestedByUserId())
                .description(dto.getDescription())
                .isEmergencyException(Boolean.TRUE.equals(dto.getIsEmergencyException()))
                .exceptionReason(dto.getExceptionReason())
                .status(ChangeRequestStatus.AWAITING_ADVISOR_REVIEW)
                .requestedAt(LocalDateTime.now())
                .build();

        changeRequest = changeRequestRepository.save(changeRequest);
        log.info("Created change request {} for work order {}", changeRequest.getId(), dto.getWorkOrderId());

        // Create associated work order services
        if (dto.getServices() != null) {
            for (CreateChangeRequestDTO.WorkOrderItemDTO serviceDto : dto.getServices()) {
                com.positivity.workorder.entity.WorkOrderService service = createWorkOrderServiceEntity(workOrder, changeRequest, serviceDto);
                workOrderServiceRepository.save(service);
            }
        }

        // Create associated work order parts
        if (dto.getParts() != null) {
            for (CreateChangeRequestDTO.WorkOrderItemDTO partDto : dto.getParts()) {
                WorkOrderPart part = createWorkOrderPart(changeRequest, partDto);
                workOrderPartRepository.save(part);
            }
        }

        // TODO: Generate supplemental PDF estimate
        // changeRequest.setSupplementalEstimatePdfId(generateSupplementalEstimate(changeRequest));
        // changeRequestRepository.save(changeRequest);

        return changeRequest;
    }

    /**
     * Approve a change request and move items to OPEN/READY_TO_EXECUTE status
     */
    @Transactional
    public ChangeRequest approveChangeRequest(Long changeRequestId, Long approvedBy, String approvalNote) {
        ChangeRequest changeRequest = changeRequestRepository.findById(changeRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Change request not found: " + changeRequestId));

        if (!changeRequest.canApprove()) {
            throw new IllegalStateException("Change request cannot be approved in current state: " + changeRequest.getStatus());
        }

        // Approval note is required as the approval artifact
        if (approvalNote == null || approvalNote.trim().isEmpty()) {
            throw new IllegalArgumentException("Approval note is required as the approval artifact");
        }

        changeRequest.setStatus(ChangeRequestStatus.APPROVED);
        changeRequest.setApprovedAt(LocalDateTime.now());
        changeRequest.setApprovedBy(approvedBy);
        changeRequest.setApprovalNote(approvalNote);

        // Move associated items from PENDING_APPROVAL to OPEN/READY_TO_EXECUTE
        updateItemsStatus(changeRequest.getId(), WorkOrderItemStatus.READY_TO_EXECUTE);

        log.info("Approved change request {} by user {}", changeRequestId, approvedBy);
        return changeRequestRepository.save(changeRequest);
    }

    /**
     * Decline a change request and move items to CANCELLED status
     */
    @Transactional
    public ChangeRequest declineChangeRequest(Long changeRequestId, String approvalNote) {
        ChangeRequest changeRequest = changeRequestRepository.findById(changeRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Change request not found: " + changeRequestId));

        if (!changeRequest.canDecline()) {
            throw new IllegalStateException("Change request cannot be declined in current state: " + changeRequest.getStatus());
        }

        // Approval note is required as the approval artifact (records the decision)
        if (approvalNote == null || approvalNote.trim().isEmpty()) {
            throw new IllegalArgumentException("Approval note is required to record the decline decision");
        }

        changeRequest.setStatus(ChangeRequestStatus.DECLINED);
        changeRequest.setDeclinedAt(LocalDateTime.now());
        changeRequest.setApprovalNote(approvalNote);

        // Move associated items from PENDING_APPROVAL to CANCELLED (not billable)
        updateItemsStatus(changeRequest.getId(), WorkOrderItemStatus.CANCELLED);

        log.info("Declined change request {}", changeRequestId);
        return changeRequestRepository.save(changeRequest);
    }

    /**
     * Record customer denial acknowledgment for emergency/safety items
     */
    @Transactional
    public void recordCustomerDenialAcknowledgment(Long changeRequestId) {
        ChangeRequest changeRequest = changeRequestRepository.findById(changeRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Change request not found: " + changeRequestId));

        if (!changeRequest.getIsEmergencyException()) {
            throw new IllegalStateException("Change request is not marked as emergency/safety exception");
        }

        if (changeRequest.getStatus() != ChangeRequestStatus.DECLINED) {
            throw new IllegalStateException("Change request must be declined to record customer denial acknowledgment");
        }

        // Mark all emergency items as acknowledged
        List<com.positivity.workorder.entity.WorkOrderService> services = workOrderServiceRepository.findByChangeRequestId(changeRequestId);
        for (com.positivity.workorder.entity.WorkOrderService service : services) {
            if (Boolean.TRUE.equals(service.getIsEmergencySafety())) {
                service.setCustomerDenialAcknowledged(true);
                workOrderServiceRepository.save(service);
            }
        }

        List<WorkOrderPart> parts = workOrderPartRepository.findByChangeRequestId(changeRequestId);
        for (WorkOrderPart part : parts) {
            if (Boolean.TRUE.equals(part.getIsEmergencySafety())) {
                part.setCustomerDenialAcknowledged(true);
                workOrderPartRepository.save(part);
            }
        }

        log.info("Recorded customer denial acknowledgment for emergency items in change request {}", changeRequestId);
    }

    /**
     * Check if work order can be closed (all emergency items must be acknowledged if declined)
     */
    public boolean canCloseWorkOrder(Long workOrderId) {
        List<ChangeRequest> emergencyRequests = changeRequestRepository.findByWorkOrderIdAndStatus(
                workOrderId, ChangeRequestStatus.DECLINED);

        for (ChangeRequest request : emergencyRequests) {
            if (Boolean.TRUE.equals(request.getIsEmergencyException())) {
                // Check if all emergency items are acknowledged
                List<com.positivity.workorder.entity.WorkOrderService> services = workOrderServiceRepository.findByChangeRequestId(request.getId());
                for (com.positivity.workorder.entity.WorkOrderService service : services) {
                    if (Boolean.TRUE.equals(service.getIsEmergencySafety()) 
                            && !Boolean.TRUE.equals(service.getCustomerDenialAcknowledged())) {
                        return false;
                    }
                }

                List<WorkOrderPart> parts = workOrderPartRepository.findByChangeRequestId(request.getId());
                for (WorkOrderPart part : parts) {
                    if (Boolean.TRUE.equals(part.getIsEmergencySafety()) 
                            && !Boolean.TRUE.equals(part.getCustomerDenialAcknowledged())) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    public List<ChangeRequest> getChangeRequestsByWorkOrder(Long workOrderId) {
        return changeRequestRepository.findByWorkOrderId(workOrderId);
    }

    public ChangeRequest getChangeRequestById(Long id) {
        return changeRequestRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Change request not found: " + id));
    }

    private void validateEmergencyDocumentation(CreateChangeRequestDTO dto) {
        boolean hasEmergencyItems = false;

        if (dto.getServices() != null) {
            for (CreateChangeRequestDTO.WorkOrderItemDTO service : dto.getServices()) {
                if (Boolean.TRUE.equals(service.getIsEmergencySafety())) {
                    hasEmergencyItems = true;
                    validateEmergencyItem(service);
                }
            }
        }

        if (dto.getParts() != null) {
            for (CreateChangeRequestDTO.WorkOrderItemDTO part : dto.getParts()) {
                if (Boolean.TRUE.equals(part.getIsEmergencySafety())) {
                    hasEmergencyItems = true;
                    validateEmergencyItem(part);
                }
            }
        }

        if (!hasEmergencyItems) {
            throw new IllegalArgumentException("Emergency exception flagged but no items are marked as emergency/safety");
        }
    }

    private void validateEmergencyItem(CreateChangeRequestDTO.WorkOrderItemDTO item) {
        // Photo evidence OR explicit "photo not possible" + notes required
        boolean hasPhoto = item.getPhotoEvidenceUrl() != null && !item.getPhotoEvidenceUrl().trim().isEmpty();
        boolean photoNotPossible = Boolean.TRUE.equals(item.getPhotoNotPossible());
        boolean hasNotes = item.getEmergencyNotes() != null && !item.getEmergencyNotes().trim().isEmpty();

        if (!hasPhoto && !photoNotPossible) {
            throw new IllegalArgumentException("Emergency/safety items require photo evidence or must mark 'photo not possible'");
        }

        if (!hasPhoto && !hasNotes) {
            throw new IllegalArgumentException("Emergency/safety items without photo must have notes explaining the situation");
        }
    }

    private com.positivity.workorder.entity.WorkOrderService createWorkOrderServiceEntity(
            WorkOrder workOrder, ChangeRequest changeRequest, 
            CreateChangeRequestDTO.WorkOrderItemDTO dto) {
        return com.positivity.workorder.entity.WorkOrderService.builder()
                .workOrder(workOrder)
                .serviceEntityId(dto.getServiceEntityId())
                .status(WorkOrderItemStatus.PENDING_APPROVAL)
                .changeRequestId(changeRequest.getId())
                .isEmergencySafety(Boolean.TRUE.equals(dto.getIsEmergencySafety()))
                .photoEvidenceUrl(dto.getPhotoEvidenceUrl())
                .emergencyNotes(dto.getEmergencyNotes())
                .photoNotPossible(Boolean.TRUE.equals(dto.getPhotoNotPossible()))
                .build();
    }

    private WorkOrderPart createWorkOrderPart(ChangeRequest changeRequest, 
                                              CreateChangeRequestDTO.WorkOrderItemDTO dto) {
        return WorkOrderPart.builder()
                .productEntityId(dto.getProductEntityId())
                .nonInventoryProductEntityId(dto.getNonInventoryProductEntityId())
                .quantity(dto.getQuantity())
                .status(WorkOrderItemStatus.PENDING_APPROVAL)
                .changeRequestId(changeRequest.getId())
                .isEmergencySafety(Boolean.TRUE.equals(dto.getIsEmergencySafety()))
                .photoEvidenceUrl(dto.getPhotoEvidenceUrl())
                .emergencyNotes(dto.getEmergencyNotes())
                .photoNotPossible(Boolean.TRUE.equals(dto.getPhotoNotPossible()))
                .build();
    }

    private void updateItemsStatus(Long changeRequestId, WorkOrderItemStatus newStatus) {
        // Update services
        List<com.positivity.workorder.entity.WorkOrderService> services = workOrderServiceRepository.findByChangeRequestId(changeRequestId);
        for (com.positivity.workorder.entity.WorkOrderService service : services) {
            service.setStatus(newStatus);
            workOrderServiceRepository.save(service);
        }

        // Update parts
        List<WorkOrderPart> parts = workOrderPartRepository.findByChangeRequestId(changeRequestId);
        for (WorkOrderPart part : parts) {
            part.setStatus(newStatus);
            workOrderPartRepository.save(part);
        }
    }
}
