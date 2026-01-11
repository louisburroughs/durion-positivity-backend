package com.positivity.workorder.service;

import com.positivity.workorder.entity.*;
import com.positivity.workorder.repository.WorkOrderRepository;
import com.positivity.workorder.repository.WorkOrderStateTransitionRepository;
import com.positivity.workorder.repository.WorkOrderSnapshotRepository;
import com.positivity.workorder.repository.ChangeRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkOrderStateMachine {
    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderStateTransitionRepository transitionRepository;
    private final WorkOrderSnapshotRepository snapshotRepository;
    private final ChangeRequestRepository changeRequestRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void transitionWorkOrder(Long workOrderId, WorkOrderStatus toStatus, Long userId, String reason) {
        WorkOrder workOrder = workOrderRepository.findById(workOrderId)
                .orElseThrow(() -> new IllegalArgumentException("WorkOrder not found: " + workOrderId));

        WorkOrderStatus fromStatus = workOrder.getStatus();

        if (!fromStatus.canTransitionTo(toStatus)) {
            throw new IllegalStateException(
                    String.format("Invalid state transition from %s to %s for WorkOrder %d",
                            fromStatus, toStatus, workOrderId));
        }

        workOrder.setStatus(toStatus);
        workOrderRepository.save(workOrder);

        recordTransition(workOrderId, fromStatus, toStatus, userId, reason);

        log.info("WorkOrder {} transitioned from {} to {} by user {}", workOrderId, fromStatus, toStatus, userId);
    }

    @Transactional
    public void startWorkOrder(Long workOrderId, Long userId, String reason) {
        WorkOrder workOrder = workOrderRepository.findById(workOrderId)
                .orElseThrow(() -> new IllegalArgumentException("WorkOrder not found: " + workOrderId));

        if (!WorkOrderStatus.getStartEligibleStatuses().contains(workOrder.getStatus())) {
            throw new IllegalStateException(
                    String.format("WorkOrder %d cannot be started from status %s. Must be one of: %s",
                            workOrderId, workOrder.getStatus(), WorkOrderStatus.getStartEligibleStatuses()));
        }

        List<ChangeRequest> pendingApprovalRequests = changeRequestRepository.findByWorkOrderIdAndStatus(
                workOrderId, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);

        if (!pendingApprovalRequests.isEmpty()) {
            throw new IllegalStateException(
                    String.format("WorkOrder %d cannot be started. There are %d pending change request(s) awaiting approval",
                            workOrderId, pendingApprovalRequests.size()));
        }

        captureSnapshot(workOrder, userId, "WORK_START", reason);

        transitionWorkOrder(workOrderId, WorkOrderStatus.WORK_IN_PROGRESS, userId, reason);
    }

    @Transactional
    public void captureSnapshot(WorkOrder workOrder, Long userId, String snapshotType, String reason) {
        try {
            String snapshotData = objectMapper.writeValueAsString(workOrder);

            WorkOrderSnapshot snapshot = WorkOrderSnapshot.builder()
                    .workOrderId(workOrder.getId())
                    .status(workOrder.getStatus())
                    .capturedBy(userId)
                    .snapshotType(snapshotType)
                    .snapshotData(snapshotData)
                    .reason(reason)
                    .capturedAt(Instant.now())
                    .build();

            snapshotRepository.save(snapshot);

            log.info("Captured snapshot {} for WorkOrder {}", snapshotType, workOrder.getId());
        } catch (Exception e) {
            log.error("Failed to capture snapshot for WorkOrder " + workOrder.getId(), e);
            throw new RuntimeException("Failed to capture work order snapshot", e);
        }
    }

    private void recordTransition(Long workOrderId, WorkOrderStatus fromStatus, WorkOrderStatus toStatus,
                                   Long userId, String reason) {
        WorkOrderStateTransition transition = WorkOrderStateTransition.builder()
                .workOrderId(workOrderId)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .transitionedBy(userId)
                .reason(reason)
                .transitionedAt(Instant.now())
                .build();

        transitionRepository.save(transition);
    }

    public List<WorkOrderStateTransition> getTransitionHistory(Long workOrderId) {
        return transitionRepository.findByWorkOrderIdOrderByTransitionedAtDesc(workOrderId);
    }

    public List<WorkOrderSnapshot> getSnapshotHistory(Long workOrderId) {
        return snapshotRepository.findByWorkOrderIdOrderByCapturedAtDesc(workOrderId);
    }
}
