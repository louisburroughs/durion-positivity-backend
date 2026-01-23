package com.positivity.workorder.service;

import com.positivity.workorder.entity.*;
import com.positivity.workorder.repository.WorkorderRepository;
import com.positivity.workorder.repository.WorkorderStateTransitionRepository;
import com.positivity.workorder.repository.WorkorderSnapshotRepository;
import com.positivity.workorder.repository.ChangeRequestRepository;
import com.positivity.workorder.repository.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkorderStateMachine {
    private final WorkorderRepository workorderRepository;
    private final WorkorderStateTransitionRepository transitionRepository;
    private final WorkorderSnapshotRepository snapshotRepository;
    private final ChangeRequestRepository changeRequestRepository;
    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;
    private final ChangeRequestService changeRequestService;

    private static final Set<WorkorderStatus> COMPLETION_ELIGIBLE_STATUSES = Set.of(
            WorkorderStatus.WORK_IN_PROGRESS,
            WorkorderStatus.AWAITING_PARTS,
            WorkorderStatus.AWAITING_APPROVAL,
            WorkorderStatus.READY_FOR_PICKUP
    );

    @Transactional
    public void transitionWorkorder(Long workorderId, WorkorderStatus toStatus, Long userId, String reason) {
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new IllegalArgumentException("Workorder not found: " + workorderId));

        WorkorderStatus fromStatus = workorder.getStatus();

        if (!fromStatus.canTransitionTo(toStatus)) {
            throw new IllegalStateException(
                    String.format("Invalid state transition from %s to %s for Workorder %d",
                            fromStatus, toStatus, workorderId));
        }

        // Block completion if there are pending approval-gated change requests
        if (isCompletionStatus(toStatus)) {
            validateCompletionRequirements(workorderId);
        }

        workorder.setStatus(toStatus);
        workorderRepository.save(workorder);

        recordTransition(workorderId, fromStatus, toStatus, userId, reason);

        log.info("Workorder {} transitioned from {} to {} by user {}", workorderId, fromStatus, toStatus, userId);
    }

    /**
     * Validate that work order meets all requirements for completion.
     * Throws IllegalStateException if requirements are not met.
     */
    private void validateCompletionRequirements(Long workorderId) {
        // Check for pending approval-gated change requests
        if (changeRequestService.hasPendingApprovalGatedRequests(workorderId)) {
            List<ChangeRequest> pendingRequests = changeRequestService.getPendingApprovalGatedRequests(workorderId);
            throw new IllegalStateException(
                    String.format("Workorder %d cannot be completed. There are %d unresolved approval-gated change request(s) pending approval",
                            workorderId, pendingRequests.size()));
        }

        // Check for declined emergency items requiring acknowledgment
        if (!changeRequestService.canCloseWorkorder(workorderId)) {
            throw new IllegalStateException(
                    String.format("Workorder %d cannot be completed. There are declined emergency/safety items that require customer denial acknowledgment",
                            workorderId));
        }
    }

    /**
     * Check if the status is a completion-related status.
     */
    private boolean isCompletionStatus(WorkorderStatus status) {
        return status == WorkorderStatus.COMPLETED || status == WorkorderStatus.READY_FOR_PICKUP;
    }

    @Transactional
    public void startWorkorder(Long workorderId, Long userId, String reason) {
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new IllegalArgumentException("Workorder not found: " + workorderId));

        if (!WorkorderStatus.getStartEligibleStatuses().contains(workorder.getStatus())) {
            throw new IllegalStateException(
                    String.format("Workorder %d cannot be started from status %s. Must be one of: %s",
                            workorderId, workorder.getStatus(), WorkorderStatus.getStartEligibleStatuses()));
        }

        List<ChangeRequest> pendingApprovalRequests = changeRequestRepository.findByWorkorderIdAndStatus(
                workorderId, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);

        if (!pendingApprovalRequests.isEmpty()) {
            throw new IllegalStateException(
                    String.format("Workorder %d cannot be started. There are %d pending change request(s) awaiting approval",
                            workorderId, pendingApprovalRequests.size()));
        }

        captureSnapshot(workorder, userId, "WORK_START", reason);

        transitionWorkorder(workorderId, WorkorderStatus.WORK_IN_PROGRESS, userId, reason);
    }

    @Transactional
    public void completeWorkorder(Long workorderId, Long userId, String completionNotes) {
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new IllegalArgumentException("Workorder not found: " + workorderId));

        WorkorderStatus currentStatus = workorder.getStatus();

        // Validate the work order is in a completable state
        if (currentStatus == WorkorderStatus.COMPLETED) {
            throw new IllegalStateException(
                    String.format("Workorder %d is already completed", workorderId));
        }

        if (currentStatus == WorkorderStatus.CANCELLED) {
            throw new IllegalStateException(
                    String.format("Workorder %d is cancelled and cannot be completed", workorderId));
        }

        if (!COMPLETION_ELIGIBLE_STATUSES.contains(currentStatus)) {
            throw new IllegalStateException(
                    String.format("Workorder %d cannot be completed from status %s. Must be one of: %s",
                            workorderId, currentStatus, COMPLETION_ELIGIBLE_STATUSES));
        }

        // Capture snapshot before completion
        captureSnapshot(workorder, userId, "WORK_COMPLETION", "Capturing state before completion");

        // Set completion fields
        Instant completedAt = Instant.now();
        workorder.setCompletedAt(completedAt);
        workorder.setCompletedBy(userId);
        workorder.setCompletionNotes(completionNotes);

        // Transition to COMPLETED state
        transitionWorkorder(workorderId, WorkorderStatus.COMPLETED, userId, "Work Order Completed");

        // Create audit event
        createAuditEvent(workorderId, userId, "StateTransition", 
                String.format("{\"fromState\":\"%s\",\"toState\":\"COMPLETED\"}", currentStatus));

        log.info("Workorder {} completed successfully by user {} at {}", workorderId, userId, completedAt);
    }

    @Transactional
    public void captureSnapshot(Workorder workorder, Long userId, String snapshotType, String reason) {
        try {
            String snapshotData = objectMapper.writeValueAsString(workorder);

            WorkorderSnapshot snapshot = WorkorderSnapshot.builder()
                    .workorderId(workorder.getId())
                    .status(workorder.getStatus())
                    .capturedBy(userId)
                    .snapshotType(snapshotType)
                    .snapshotData(snapshotData)
                    .reason(reason)
                    .capturedAt(Instant.now())
                    .build();

            snapshotRepository.save(snapshot);

            log.info("Captured snapshot {} for Workorder {}", snapshotType, workorder.getId());
        } catch (Exception e) {
            log.error("Failed to capture snapshot for Workorder " + workorder.getId(), e);
            throw new RuntimeException("Failed to capture work order snapshot", e);
        }
    }

    private void recordTransition(Long workorderId, WorkorderStatus fromStatus, WorkorderStatus toStatus,
                                   Long userId, String reason) {
        WorkorderStateTransition transition = WorkorderStateTransition.builder()
                .workorderId(workorderId)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .transitionedBy(userId)
                .reason(reason)
                .transitionedAt(Instant.now())
                .build();

        transitionRepository.save(transition);
    }

    public List<WorkorderStateTransition> getTransitionHistory(Long workorderId) {
        return transitionRepository.findByWorkorderIdOrderByTransitionedAtDesc(workorderId);
    }

    public List<WorkorderSnapshot> getSnapshotHistory(Long workorderId) {
        return snapshotRepository.findByWorkorderIdOrderByCapturedAtDesc(workorderId);
    }

    private void createAuditEvent(Long workorderId, Long userId, String eventType, String details) {
        AuditEvent auditEvent = AuditEvent.builder()
                .entityType("Workorder")
                .entityId(workorderId)
                .eventType(eventType)
                .userId(userId)
                .details(details)
                .eventTimestamp(Instant.now())
                .build();

        auditEventRepository.save(auditEvent);
        log.debug("Created audit event for Workorder {}: {}", workorderId, eventType);
    }

    public List<AuditEvent> getAuditHistory(Long workorderId) {
        return auditEventRepository.findByEntityTypeAndEntityIdOrderByEventTimestampDesc("Workorder", workorderId);
    }
}
