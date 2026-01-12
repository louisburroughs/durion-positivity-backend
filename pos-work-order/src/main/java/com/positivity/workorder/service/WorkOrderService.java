package com.positivity.workorder.service;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.entity.Estimate;
import com.positivity.workorder.entity.WorkOrder;
import com.positivity.workorder.entity.WorkOrderStatus;
import com.positivity.workorder.event.WorkCompletedEvent;
import com.positivity.workorder.repository.WorkOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkOrderService {
    private final WorkOrderRepository workOrderRepository;
    private final RestTemplate restTemplate;
    private final EstimateService estimateService;
    private final WorkOrderStateMachine stateMachine;

    @Value("${customer.service.url:http://localhost:8080/api/customers}")
    private String customerServiceUrl;
    @Value("${customer.approval.service.url:http://localhost:8080/api/approvals}")
    private String customerApprovalServiceUrl;

    public List<WorkOrder> getAllWorkOrders() {
        return workOrderRepository.findAll();
    }

    public Optional<WorkOrder> getWorkOrderById(Long id) {
        return workOrderRepository.findById(id);
    }

    @Transactional
    public WorkOrder createWorkOrder(WorkOrder workOrder) {
        // Check customer requirements from pos-customer
        if (!checkCustomerRequirements(workOrder.getCustomerId())) {
            throw new IllegalArgumentException("Customer requirements not met");
        }
        
        // If estimateId is provided, validate the estimate is approved
        if (workOrder.getEstimateId() != null) {
            Estimate estimate = estimateService.getEstimateById(workOrder.getEstimateId())
                    .orElseThrow(() -> new IllegalArgumentException("Estimate not found: " + workOrder.getEstimateId()));
            
            if (estimate.getStatus() != Estimate.EstimateStatus.APPROVED) {
                throw new IllegalArgumentException("Work order can only be created from an approved estimate. Current status: " + estimate.getStatus());
            }
            
            log.info("Creating work order from approved estimate {}", workOrder.getEstimateId());
        }
        
        // Check customer approval from pos-customer-approval (legacy)
        if (workOrder.getApprovalId() != null && !checkCustomerApproval(workOrder.getApprovalId())) {
            throw new IllegalArgumentException("Customer approval not found or not valid");
        }
        
        return workOrderRepository.save(workOrder);
    }

    public void deleteWorkOrder(Long id) {
        workOrderRepository.deleteById(id);
    }

    private boolean checkCustomerRequirements(Long customerId) {
        try {
            Boolean result = restTemplate.getForObject(customerServiceUrl + "/" + customerId + "/requirements-met", Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Failed to check customer requirements", e);
            return false;
        }
    }

    private boolean checkCustomerApproval(Long approvalId) {
        try {
            Boolean result = restTemplate.getForObject(customerApprovalServiceUrl + "/" + approvalId + "/is-approved", Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Failed to check customer approval", e);
            return false;
        }
    }

    @Transactional
    public void startWorkOrder(Long workOrderId, Long userId, String reason) {
        stateMachine.startWorkOrder(workOrderId, userId, reason);
    }

    @Transactional
    public void transitionWorkOrder(Long workOrderId, WorkOrderStatus toStatus, Long userId, String reason) {
        stateMachine.transitionWorkOrder(workOrderId, toStatus, userId, reason);
    }

    public List<com.positivity.workorder.entity.WorkOrderStateTransition> getTransitionHistory(Long workOrderId) {
        return stateMachine.getTransitionHistory(workOrderId);
    }

    public List<com.positivity.workorder.entity.WorkOrderSnapshot> getSnapshotHistory(Long workOrderId) {
        return stateMachine.getSnapshotHistory(workOrderId);
    }

    @Transactional
    @EmitEvent(id = "WorkCompleted")
    public WorkCompletedEvent completeWorkOrder(Long workOrderId, Long userId, String completionNotes) {
        // Perform the completion logic
        stateMachine.completeWorkOrder(workOrderId, userId, completionNotes);

        // Retrieve the updated work order
        WorkOrder workOrder = workOrderRepository.findById(workOrderId)
                .orElseThrow(() -> new IllegalArgumentException("WorkOrder not found after completion: " + workOrderId));

        // Build the final billable scope
        Map<String, Object> finalBillableScope = buildFinalBillableScope(workOrder);

        // Create and return the event
        String eventId = UUID.randomUUID().toString();
        String idempotencyKey = String.format("%d:completion_%s", workOrderId, workOrder.getCompletedAt().toEpochMilli());

        WorkCompletedEvent.WorkCompletedPayload payload = WorkCompletedEvent.WorkCompletedPayload.builder()
                .workOrderId(workOrderId)
                .completedAt(workOrder.getCompletedAt())
                .completedBy(workOrder.getCompletedBy())
                .finalBillableScope(finalBillableScope)
                .build();

        WorkCompletedEvent event = WorkCompletedEvent.builder()
                .eventId(eventId)
                .eventType("WorkCompleted")
                .eventTimestamp(Instant.now())
                .sourceDomain("workexec")
                .idempotencyKey(idempotencyKey)
                .payload(payload)
                .build();

        log.info("WorkCompleted event created with eventId={} for workOrderId={}", eventId, workOrderId);
        return event;
    }

    private Map<String, Object> buildFinalBillableScope(WorkOrder workOrder) {
        Map<String, Object> scope = new HashMap<>();
        scope.put("workOrderId", workOrder.getId());
        scope.put("services", workOrder.getServices() != null ? workOrder.getServices().size() : 0);
        // Additional billable items (parts, labor, fees) can be added here
        scope.put("status", workOrder.getStatus().toString());
        scope.put("completedAt", workOrder.getCompletedAt());
        return scope;
    }
}

