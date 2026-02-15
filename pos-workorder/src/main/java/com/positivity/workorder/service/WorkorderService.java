package com.positivity.workorder.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import com.positivity.workorder.internal.entity.AuditEvent;
import com.positivity.workorder.internal.entity.ApprovalStatus;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderItemStatus;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderStatus;
import com.positivity.workorder.internal.event.EstimateRevisedEvent;
import com.positivity.workorder.internal.event.WorkCompletedEvent;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkorderService {
    private final WorkorderRepository workorderRepository;
    private final EstimateRepository estimateRepository;
    private final EstimateItemRepository estimateItemRepository;
    private final WorkorderServiceRepository workorderServiceRepository;
    private final WorkorderPartRepository workorderPartRepository;
    private final RestClient restClient;
    private final WorkorderStateMachine stateMachine;
    private final AuditEventRepository auditEventRepository;
    private final IdempotencyService idempotencyService;
    private final PromotionValidationService promotionValidationService;

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
        // If customerId is null, fetch it from the estimate
        if (customerId == null && estimateId != null) {
            Estimate estimate = estimateRepository.findById(estimateId)
                    .orElseThrow(() -> new IllegalArgumentException("Estimate not found: " + estimateId));
            customerId = estimate.getCustomerId();
            log.debug("Fetched customerId {} from estimate {}", customerId, estimateId);
        }

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
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                idempotencyService.registerKey(idempotencyKey, created.getId());
            } catch (DataIntegrityViolationException e) {
                // Race condition: another request already registered this key
                // Check if it points to the same workorder or a different one
                Optional<UUID> existingWorkorderId = idempotencyService.getExistingWorkorderId(idempotencyKey);
                if (existingWorkorderId.isPresent() && !existingWorkorderId.get().equals(created.getId())) {
                    log.warn("Race condition detected: idempotency key {} already registered for different workorder {}",
                            idempotencyKey, existingWorkorderId.get());
                    // Return the existing workorder to maintain idempotency semantics
                    return workorderRepository.findById(existingWorkorderId.get())
                            .orElse(created); // Fallback to current if not found
                }
                // If it points to the same workorder, we can proceed normally
                log.debug("Idempotency key {} already registered for current workorder {}", 
                        idempotencyKey, created.getId());
            }
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

        // If estimateId is provided, validate promotion preconditions
        if (workorder.getEstimateId() != null) {
            log.info("Validating promotion preconditions for estimate {}", workorder.getEstimateId());

            // Use PromotionValidationService for comprehensive validation (CAP:004 Story
            // #25)
            promotionValidationService.validatePromotionPreconditions(workorder.getEstimateId());

            log.info("Promotion preconditions validated successfully for estimate {}",
                    workorder.getEstimateId());
        }

        // Check customer approval from pos-customer-approval (legacy)
        if (workorder.getApprovalId() != null && !checkCustomerApproval(workorder.getApprovalId())) {
            throw new IllegalArgumentException("Customer approval not found or not valid");
        }

        // Save the workorder first to get the persisted entity
        Workorder savedWorkorder = workorderRepository.save(workorder);

        // CAP:004 Story #27 - Copy estimate items to workorder items if promoting from
        // estimate
        if (savedWorkorder.getEstimateId() != null) {
            log.info("Copying estimate items to workorder items for estimate {} and workorder {}",
                    savedWorkorder.getEstimateId(), savedWorkorder.getId());
            copyEstimateItemsToWorkorder(savedWorkorder);
        }

        return savedWorkorder;
    }

    /**
     * Copy approved estimate items to workorder items (CAP:004 Story #27, #29).
     * Creates an immutable financial snapshot of pricing, quantity, and tax
     * information.
     * 
     * Story #29: Only copies items with ApprovalStatus.APPROVED to support
     * partial approval workflows where customers can approve a subset of items.
     * 
     * @param workorder the workorder to populate with items
     */
    private void copyEstimateItemsToWorkorder(Workorder workorder) {
        UUID estimateId = workorder.getEstimateId();
        if (estimateId == null) {
            log.warn("Workorder {} has no estimateId, skipping item copy", workorder.getId());
            return;
        }

        // CAP:004 Story #29: Fetch only APPROVED and non-deleted items to support
        // partial approval
        List<EstimateItem> estimateItems = estimateItemRepository
                .findByEstimateIdAndApprovalStatusAndDeletedFalse(estimateId, ApprovalStatus.APPROVED);

        if (estimateItems.isEmpty()) {
            log.warn("No approved estimate items found for estimate {}, no workorder items created", estimateId);
            return;
        }

        log.info("Found {} approved estimate items to copy to workorder {}",
                estimateItems.size(), workorder.getId());

        List<com.positivity.workorder.internal.entity.WorkorderService> laborItems = new ArrayList<>();
        List<WorkorderPart> partItems = new ArrayList<>();

        for (EstimateItem estimateItem : estimateItems) {
            if (estimateItem.getItemType() == EstimateItemType.LABOR) {
                // Create WorkorderService for LABOR items
                com.positivity.workorder.internal.entity.WorkorderService workorderService = com.positivity.workorder.internal.entity.WorkorderService
                        .builder()
                        .workOrder(workorder)
                        .description(estimateItem.getDescription())
                        .quantity(estimateItem.getQuantity())
                        .unitPrice(estimateItem.getUnitPrice())
                        .lineTotal(estimateItem.getLineTotal())
                        .taxCode(estimateItem.getTaxCode())
                        .originEstimateItemId(estimateItem.getId())
                        .serviceEntityId(estimateItem.getServiceId())
                        .status(WorkorderItemStatus.OPEN) // Initial status: OPEN (Authorized in contract)
                        .declined(false)
                        .isEmergencySafety(false)
                        .photoNotPossible(false)
                        .build();

                laborItems.add(workorderService);

            } else if (estimateItem.getItemType() == EstimateItemType.PART) {
                // Create WorkorderPart for PART items
                // CAP:004 Story #27: Parts can be standalone (not tied to a service)
                WorkorderPart workorderPart = WorkorderPart.builder()
                        .workOrderService(null) // Standalone part not tied to a service
                        .workorder(workorder) // Direct reference to workorder for standalone parts
                        .description(estimateItem.getDescription())
                        .quantity(estimateItem.getQuantity())
                        .unitPrice(estimateItem.getUnitPrice())
                        .lineTotal(estimateItem.getLineTotal())
                        .taxCode(estimateItem.getTaxCode())
                        .originEstimateItemId(estimateItem.getId())
                        .productEntityId(estimateItem.getProductId())
                        .status(WorkorderItemStatus.OPEN) // Initial status: OPEN (Authorized in contract)
                        .declined(false)
                        .isEmergencySafety(false)
                        .photoNotPossible(false)
                        .build();

                partItems.add(workorderPart);
            } else {
                log.warn("Unsupported EstimateItemType {} for estimate item {}, skipping",
                        estimateItem.getItemType(), estimateItem.getId());
            }
        }

        // Persist all labor items
        if (!laborItems.isEmpty()) {
            workorderServiceRepository.saveAll(laborItems);
            log.info("Persisted {} labor items for workorder {}", laborItems.size(), workorder.getId());
            laborItems.forEach(item -> log.debug("Created workorder service item with ID {}: from estimate item {}",
                    item.getId(), item.getOriginEstimateItemId()));
        }

        // Persist all part items
        if (!partItems.isEmpty()) {
            workorderPartRepository.saveAll(partItems);
            log.info("Persisted {} part items for workorder {}", partItems.size(), workorder.getId());
            partItems.forEach(item -> log.debug("Created workorder part item with ID {}: from estimate item {}",
                    item.getId(), item.getOriginEstimateItemId()));
        }

        log.info("Successfully copied {} estimate items to workorder {} ({} labor, {} parts)",
                estimateItems.size(), workorder.getId(), laborItems.size(), partItems.size());
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
