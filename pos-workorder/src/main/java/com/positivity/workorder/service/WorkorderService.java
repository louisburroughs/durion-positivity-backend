package com.positivity.workorder.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import com.positivity.workorder.internal.dto.EstimateResponse;
import com.positivity.workorder.internal.entity.AuditEvent;
import com.positivity.workorder.internal.entity.EstimateStatus;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderStatus;
import com.positivity.workorder.internal.event.EstimateRevisedEvent;
import com.positivity.workorder.internal.event.WorkCompletedEvent;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkorderService {
    private final WorkorderRepository workorderRepository;
    private final RestClient restClient;
    private final EstimateService estimateService;
    private final WorkorderStateMachine stateMachine;
    private final AuditEventRepository auditEventRepository;
    private final IdempotencyService idempotencyService;

    @Value("${customer.service.url:http://localhost:8080/api/customers}")
    private String customerServiceUrl;
    @Value("${customer.approval.service.url:http://localhost:8080/api/approvals}")
    private String customerApprovalServiceUrl;

    public List<Workorder> getAllWorkorders() {
        return workorderRepository.findAll();
    }

    public Optional<Workorder> getWorkorderById(UUID id) {
        return workorderRepository.findById(id);
    }

    @Transactional
    public Workorder createWorkorder(UUID estimateId, UUID customerId) {
        Workorder workorder = Workorder.builder()
                .estimateId(estimateId)
                .customerId(customerId)
                .status(WorkorderStatus.DRAFT)
                .build();
        return createWorkorderInternal(workorder);
    }

    /**
     * Create a workorder with idempotency key support.
     * 
     * <p>If an idempotency key is provided and has been processed before,
     * returns the existing workorder instead of creating a duplicate.</p>
     * 
     * @param estimateId the estimate ID
     * @param customerId the customer ID
     * @param idempotencyKey optional idempotency key for duplicate prevention; if null, idempotency is not enforced
     * @return the created or existing workorder
     */
    @Transactional
    public Workorder createWorkorderWithIdempotency(UUID estimateId, UUID customerId, String idempotencyKey) {
        // Check for existing workorder if idempotency key is provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingWorkorderId = idempotencyService.getExistingWorkorderId(idempotencyKey);
            if (existingWorkorderId.isPresent()) {
                log.info("Idempotent request detected for key {}; returning existing workorder {}", 
                         idempotencyKey, existingWorkorderId.get());
                return workorderRepository.findById(existingWorkorderId.get())
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotency key points to non-existent workorder: " + existingWorkorderId.get()));
            }
        }

        // Create new workorder
        Workorder workorder = Workorder.builder()
                .estimateId(estimateId)
                .customerId(customerId)
                .status(WorkorderStatus.DRAFT)
                .build();
        Workorder created = createWorkorderInternal(workorder);

        // Register idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            idempotencyService.registerKey(idempotencyKey, created.getId());
        }

        return created;
    }

    @Transactional
    public Workorder createWorkorder(Workorder workorder) {
        return createWorkorderInternal(workorder);
    }

    private Workorder createWorkorderInternal(Workorder workorder) {
        // Check customer requirements from pos-customer
        if (!checkCustomerRequirements(workorder.getCustomerId())) {
            throw new IllegalArgumentException("Customer requirements not met");
        }

        // If estimateId is provided, validate the estimate is approved
        if (workorder.getEstimateId() != null) {
            EstimateResponse estimate = estimateService.getEstimateById(workorder.getEstimateId())
                    .orElseThrow(
                            () -> new IllegalArgumentException("Estimate not found: " + workorder.getEstimateId()));

            EstimateStatus estimateStatus = estimate.getStatus() != null
                    ? EstimateStatus.valueOf(estimate.getStatus())
                    : null;

            if (estimateStatus != EstimateStatus.APPROVED) {
                throw new IllegalArgumentException(
                        "Workorder can only be created from an approved estimate. Current status: "
                                + estimate.getStatus());
            }

            log.info("Creating workorder from approved estimate {}", workorder.getEstimateId());
        }

        // Check customer approval from pos-customer-approval (legacy)
        if (workorder.getApprovalId() != null && !checkCustomerApproval(workorder.getApprovalId())) {
            throw new IllegalArgumentException("Customer approval not found or not valid");
        }

        return workorderRepository.save(workorder);
    }

    public void deleteWorkorder(UUID id) {
        workorderRepository.deleteById(id);
    }

    private boolean checkCustomerRequirements(UUID customerId) {
        try {
            Boolean result = restClient.get()
                    .uri(customerServiceUrl + "/" + customerId + "/requirements-met")
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Failed to check customer requirements", e);
            return false;
        }
    }

    private boolean checkCustomerApproval(UUID approvalId) {
        try {
            Boolean result = restClient.get()
                    .uri(customerApprovalServiceUrl + "/" + approvalId + "/is-approved")
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Failed to check customer approval", e);
            return false;
        }
    }

    @Transactional
    public void startWorkorder(UUID workorderId, UUID userId, String reason) {
        stateMachine.startWorkorder(workorderId, userId, reason);
    }

    @Transactional
    public Workorder approveWorkorder(UUID workorderId, UUID customerId, String signatureData,
            String signatureMimeType, String signerName, String notes) {
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new IllegalArgumentException("Workorder not found: " + workorderId));

        // Validate customer matches workorder
        if (!workorder.getCustomerId().equals(customerId)) {
            throw new IllegalArgumentException("Customer ID mismatch: workorder belongs to customer "
                    + workorder.getCustomerId() + ", but approval attempted for customer " + customerId);
        }

        // Validate workorder can be approved (must be in DRAFT status)
        if (workorder.getStatus() != WorkorderStatus.DRAFT) {
            throw new IllegalStateException("Workorder cannot be approved in current state: " + workorder.getStatus()
                    + ". Workorders can only be approved from DRAFT status.");
        }

        // Transition to APPROVED status
        workorder.setStatus(WorkorderStatus.APPROVED);
        workorder.setApprovedAt(Instant.now());
        workorder.setApprovedBy(customerId);
        workorder.setSignatureData(signatureData);
        workorder.setSignatureMimeType(signatureMimeType);
        workorder.setSignerName(signerName);
        workorder.setApprovalNotes(notes);

        log.info("Workorder {} approved by customer {} with signature capture", workorderId, customerId);
        return workorderRepository.save(workorder);
    }

    @Transactional
    public void transitionWorkorder(UUID workorderId, WorkorderStatus toStatus, UUID userId, String reason) {
        stateMachine.transitionWorkorder(workorderId, toStatus, userId, reason);
    }

    public List<com.positivity.workorder.internal.entity.WorkorderStateTransition> getTransitionHistory(
            UUID workorderId) {
        return stateMachine.getTransitionHistory(workorderId);
    }

    public List<com.positivity.workorder.internal.entity.WorkorderSnapshot> getSnapshotHistory(UUID workorderId) {
        return stateMachine.getSnapshotHistory(workorderId);
    }

    @Transactional
    public WorkCompletedEvent completeWorkorder(UUID workorderId, UUID userId, String completionNotes) {
        // Perform the completion logic
        stateMachine.completeWorkorder(workorderId, userId, completionNotes);

        // Retrieve the updated work order
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Workorder not found after completion: " + workorderId));

        // Build the final billable scope
        Map<String, Object> finalBillableScope = buildFinalBillableScope(workorder);

        // Create and return the event
        String eventId = UUID.randomUUID().toString();
        String idempotencyKey = String.format("%s:completion_%s", workorderId,
                workorder.getCompletedAt().toEpochMilli());

        WorkCompletedEvent.WorkCompletedPayload payload = WorkCompletedEvent.WorkCompletedPayload.builder()
                .workorderId(workorderId)
                .completedAt(workorder.getCompletedAt())
                .completedBy(workorder.getCompletedBy())
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

        log.info("WorkCompleted event created with eventId={} for workorderId={}", eventId, workorderId);
        return event;
    }

    private Map<String, Object> buildFinalBillableScope(Workorder workorder) {
        Map<String, Object> scope = new HashMap<>();
        scope.put("workorderId", workorder.getId());
        scope.put("services", workorder.getServices() != null ? workorder.getServices().size() : 0);
        // Additional billable items (parts, labor, fees) can be added here
        scope.put("status", workorder.getStatus().toString());
        scope.put("completedAt", workorder.getCompletedAt());
        return scope;
    }

    /**
     * Listen for EstimateRevisedEvent and invalidate Workorder approval if needed.
     * This implements the automatic approval invalidation workflow when estimates
     * are financially revised.
     * 
     * @param event the EstimateRevisedEvent containing revision details
     */
    @EventListener
    @Transactional
    public void onEstimateRevised(EstimateRevisedEvent event) {
        log.info("Received EstimateRevisedEvent: estimateId={}, workorderId={}, " +
                "oldTotal={}, newTotal={}",
                event.getEstimateId(), event.getWorkorderId(),
                event.getOldTotal(), event.getNewTotal());

        Optional<Workorder> workorderOpt = workorderRepository.findById(event.getWorkorderId());

        if (workorderOpt.isEmpty()) {
            log.warn("Workorder {} not found for EstimateRevisedEvent", event.getWorkorderId());
            return;
        }

        Workorder workorder = workorderOpt.get();

        // Only invalidate approval if Workorder is currently in APPROVED status
        if (workorder.getStatus() != WorkorderStatus.APPROVED) {
            log.info("Workorder {} not in APPROVED status (current: {}), skipping invalidation",
                    workorder.getId(), workorder.getStatus());
            return;
        }

        // Capture old status for audit
        WorkorderStatus oldStatus = workorder.getStatus();

        // Transition to AWAITING_APPROVAL (re-approval required)
        workorder.setStatus(WorkorderStatus.AWAITING_APPROVAL);
        workorderRepository.save(workorder);

        // Create audit event for traceability
        createApprovalInvalidationAudit(workorder, oldStatus, event);

        log.info("Workorder {} approval invalidated due to estimate revision. " +
                "Status changed from {} to {}. Old total: {}, New total: {}",
                workorder.getId(), oldStatus, WorkorderStatus.AWAITING_APPROVAL,
                event.getOldTotal(), event.getNewTotal());
    }

    /**
     * Create audit event for approval invalidation.
     * This provides traceability for compliance and debugging.
     */
    private void createApprovalInvalidationAudit(Workorder workorder,
            WorkorderStatus oldStatus,
            EstimateRevisedEvent event) {
        AuditEvent audit = AuditEvent.builder()
                .entityType("Workorder")
                .entityId(workorder.getId())
                .eventType("approval.invalidated")
                .eventTimestamp(event.getTimestamp())
                .userId(event.getChangedBy())
                .details(String.format(
                        "Approval invalidated due to estimate revision. " +
                                "Previous status: %s, New status: %s, " +
                                "EstimateId: %s, Old total: %s, New total: %s, Change: %s",
                        oldStatus.name(),
                        WorkorderStatus.AWAITING_APPROVAL.name(),
                        event.getEstimateId(),
                        event.getOldTotal(),
                        event.getNewTotal(),
                        event.getChangeAmount()))
                .build();

        auditEventRepository.save(audit);

        log.info("Created audit event for approval invalidation: workorderId={}, " +
                "estimateId={}", workorder.getId(), event.getEstimateId());
    }
}
