package com.positivity.workorder.service;

import com.positivity.workorder.internal.entity.*;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderStateTransitionRepository;
import com.positivity.workorder.internal.repository.WorkorderSnapshotRepository;
import com.positivity.workorder.internal.repository.ChangeRequestRepository;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
            WorkorderStatus.READY_FOR_PICKUP);

    @Transactional
    public void transitionWorkorder(UUID workorderId, WorkorderStatus toStatus, UUID userId, String reason) {
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new IllegalArgumentException("Workorder not found: " + workorderId));

        WorkorderStatus fromStatus = workorder.getStatus();

        if (!fromStatus.canTransitionTo(toStatus)) {
            throw new IllegalStateException(
                    String.format("Invalid state transition from %s to %s for Workorder %s",
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
    private void validateCompletionRequirements(UUID workorderId) {
        // Check for pending approval-gated change requests
        if (changeRequestService.hasPendingApprovalGatedRequests(workorderId)) {
            List<ChangeRequest> pendingRequests = changeRequestService.getPendingApprovalGatedRequests(workorderId);
            throw new IllegalStateException(
                    String.format(
                            "Workorder %s cannot be completed. There are %s unresolved approval-gated change request(s) pending approval",
                            workorderId, pendingRequests.size()));
        }

        // Check for declined emergency items requiring acknowledgment
        if (!changeRequestService.canCloseWorkorder(workorderId)) {
            throw new IllegalStateException(
                    String.format(
                            "Workorder %s cannot be completed. There are declined emergency/safety items that require customer denial acknowledgment",
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
    public void startWorkorder(UUID workorderId, UUID userId, String reason) {
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new IllegalArgumentException("Workorder not found: " + workorderId));

        if (!WorkorderStatus.getStartEligibleStatuses().contains(workorder.getStatus())) {
            throw new IllegalStateException(
                    String.format("Workorder %s cannot be started from status %s. Must be one of: %s",
                            workorderId, workorder.getStatus(), WorkorderStatus.getStartEligibleStatuses()));
        }

        List<ChangeRequest> pendingApprovalRequests = changeRequestRepository.findByWorkorderIdAndStatus(
                workorderId, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);

        if (!pendingApprovalRequests.isEmpty()) {
            throw new IllegalStateException(
                    String.format(
                            "Workorder %s cannot be started. There are %s pending change request(s) awaiting approval",
                            workorderId, pendingApprovalRequests.size()));
        }

        captureSnapshot(workorder, userId, "WORK_START", reason);

        transitionWorkorder(workorderId, WorkorderStatus.WORK_IN_PROGRESS, userId, reason);
    }

    @Transactional
    // TODO: Consider updating this method to accept String username instead of UUID userId
    // for consistency with the new user tracking pattern (see EstimateService changes)
    public void completeWorkorder(UUID workorderId, UUID userId, String completionNotes) {
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new IllegalArgumentException("Workorder not found: " + workorderId));

        WorkorderStatus currentStatus = workorder.getStatus();

        // Validate the work order is in a completable state
        if (currentStatus == WorkorderStatus.COMPLETED) {
            throw new IllegalStateException(
                    String.format("Workorder %s is already completed", workorderId));
        }

        if (currentStatus == WorkorderStatus.CANCELLED) {
            throw new IllegalStateException(
                    String.format("Workorder %s is cancelled and cannot be completed", workorderId));
        }

        if (!COMPLETION_ELIGIBLE_STATUSES.contains(currentStatus)) {
            throw new IllegalStateException(
                    String.format("Workorder %s cannot be completed from status %s. Must be one of: %s",
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
        createAuditEvent(workorderId, userId.toString(), "StateTransition",
                String.format("{\"fromState\":\"%s\",\"toState\":\"COMPLETED\"}", currentStatus));

        log.info("Workorder {} completed successfully by user {} at {}", workorderId, userId, completedAt);
    }

    @Transactional
    public void captureSnapshot(Workorder workorder, UUID userId, String snapshotType, String reason) {
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

    private void recordTransition(UUID workorderId, WorkorderStatus fromStatus, WorkorderStatus toStatus,
            UUID userId, String reason) {
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

    public List<WorkorderStateTransition> getTransitionHistory(UUID workorderId) {
        return transitionRepository.findByWorkorderIdOrderByTransitionedAtDesc(workorderId);
    }

    public List<WorkorderSnapshot> getSnapshotHistory(UUID workorderId) {
        return snapshotRepository.findByWorkorderIdOrderByCapturedAtDesc(workorderId);
    }

    private void createAuditEvent(UUID workorderId, String username, String eventType, String details) {
        AuditEvent auditEvent = AuditEvent.builder()
                .entityType("Workorder")
                .entityId(workorderId)
                .eventType(eventType)
                .userId(username)
                .details(details)
                .eventTimestamp(Instant.now())
                .build();

        auditEventRepository.save(auditEvent);
        log.debug("Created audit event for Workorder {}: {}", workorderId, eventType);
    }

    public List<AuditEvent> getAuditHistory(UUID workorderId) {
        return auditEventRepository.findByEntityTypeAndEntityIdOrderByEventTimestampDesc("Workorder", workorderId);
    }
}
