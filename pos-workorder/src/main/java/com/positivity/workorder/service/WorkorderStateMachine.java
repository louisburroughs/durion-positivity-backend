package com.positivity.workorder.service;

import com.positivity.workorder.internal.entity.*;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.internal.repository.WorkorderStateTransitionRepository;
import com.positivity.workorder.internal.repository.WorkorderSnapshotRepository;
import com.positivity.workorder.internal.repository.ChangeRequestRepository;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.security.common.SecurityContextHelper;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkorderStateMachine {
    private final WorkorderRepository workorderRepository;
    private final WorkorderStateTransitionRepository transitionRepository;
    private final WorkorderSnapshotRepository snapshotRepository;
    private final WorkorderServiceRepository workorderServiceRepository;
    private final WorkorderPartRepository workorderPartRepository;
    private final ChangeRequestRepository changeRequestRepository;
    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;
    private final ChangeRequestService changeRequestService;

    private static final Set<WorkorderStatus> COMPLETION_ELIGIBLE_STATUSES = Set.of(
            WorkorderStatus.WORK_IN_PROGRESS,
            WorkorderStatus.AWAITING_PARTS,
            WorkorderStatus.AWAITING_APPROVAL,
            WorkorderStatus.READY_FOR_PICKUP);

    private static final Set<WorkorderItemStatus> TERMINAL_ITEM_STATUSES = Set.of(
            WorkorderItemStatus.COMPLETED,
            WorkorderItemStatus.CANCELLED);

    private static final Set<WorkorderItemStatus> EXCLUDED_BILLABLE_TOTAL_STATUSES = Set.of(
            WorkorderItemStatus.CANCELLED);

    private static final String WORKORDER_NOT_FOUND = "Workorder not found: ";
    private static final String STATUS_FIELD = "status";
    private static final String SYSTEM_ACTOR = "system";

    public record CompletionPreconditions(
            UUID workorderId,
            String currentStatus,
            boolean canComplete,
            List<String> checklistItems,
            List<String> blockingReasons,
            int unresolvedApprovalGatedChangeRequests,
            int nonTerminalServiceItems,
            int nonTerminalPartItems,
            boolean emergencyDenialAcknowledged,
            boolean hasBillableItems) {
    }

    public void transitionWorkorder(UUID workorderId, WorkorderStatus toStatus, UUID userId, String reason) {
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new IllegalArgumentException(WORKORDER_NOT_FOUND + workorderId));

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

    private void validateCompletionRequirements(UUID workorderId) {
        CompletionPreconditions preconditions = evaluateCompletionPreconditions(workorderId);
        if (!preconditions.canComplete()) {
            String message = preconditions.blockingReasons().isEmpty()
                    ? String.format("Workorder %s cannot be completed due to unmet preconditions", workorderId)
                    : String.join("; ", preconditions.blockingReasons());
            throw new IllegalStateException(message);
        }
    }

    public CompletionPreconditions evaluateCompletionPreconditions(UUID workorderId) {
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new IllegalArgumentException(WORKORDER_NOT_FOUND + workorderId));

        List<ChangeRequest> unresolvedApprovalGated = changeRequestRepository
                .findByWorkorderIdAndStatus(workorderId, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW)
                .stream()
                .filter(changeRequest -> Boolean.TRUE.equals(changeRequest.getIsApprovalGated()))
                .toList();

        List<com.positivity.workorder.internal.entity.WorkorderService> services = workorderServiceRepository
                .findByWorkOrder_Id(workorderId);

        List<WorkorderPart> parts = loadDeduplicatedWorkorderParts(workorderId);

        long nonTerminalServiceItems = services.stream()
                .filter(service -> service.getStatus() == null || !TERMINAL_ITEM_STATUSES.contains(service.getStatus()))
                .count();

        long nonTerminalPartItems = parts.stream()
                .filter(part -> part.getStatus() == null || !TERMINAL_ITEM_STATUSES.contains(part.getStatus()))
                .count();

        boolean emergencyDenialAcknowledged = changeRequestService.canCloseWorkorder(workorderId);
        boolean hasBillableItems = !services.isEmpty() || !parts.isEmpty();

        List<String> blockingReasons = new ArrayList<>();
        if (!unresolvedApprovalGated.isEmpty()) {
            blockingReasons.add(String.format(
                    "Workorder %s cannot be completed. There are %s unresolved approval-gated change request(s) pending approval",
                    workorderId, unresolvedApprovalGated.size()));
        }
        if (nonTerminalServiceItems > 0) {
            blockingReasons.add(String.format(
                    "Workorder %s cannot be completed. There are %s service item(s) not in COMPLETED/CANCELLED state",
                    workorderId, nonTerminalServiceItems));
        }
        if (nonTerminalPartItems > 0) {
            blockingReasons.add(String.format(
                    "Workorder %s cannot be completed. There are %s part item(s) not in COMPLETED/CANCELLED state",
                    workorderId, nonTerminalPartItems));
        }
        if (!emergencyDenialAcknowledged) {
            blockingReasons.add(String.format(
                    "Workorder %s cannot be completed. There are declined emergency/safety items that require customer denial acknowledgment",
                    workorderId));
        }
        if (!hasBillableItems) {
            blockingReasons.add(String.format(
                    "Workorder %s cannot be completed. There are no billable service or part items for final snapshot",
                    workorderId));
        }
        List<String> checklistItems = List.of(
                "All workorder services are COMPLETED or CANCELLED",
                "All workorder parts are COMPLETED or CANCELLED",
                "No unresolved approval-gated change requests",
                "All emergency/safety denial acknowledgments are captured",
                "At least one billable service or part exists for final snapshot");

        boolean canComplete = blockingReasons.isEmpty();

        return new CompletionPreconditions(
                workorderId,
                workorder.getStatus().name(),
                canComplete,
                checklistItems,
                blockingReasons,
                unresolvedApprovalGated.size(),
                (int) nonTerminalServiceItems,
                (int) nonTerminalPartItems,
                emergencyDenialAcknowledged,
                hasBillableItems);
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
                .orElseThrow(() -> new IllegalArgumentException(WORKORDER_NOT_FOUND + workorderId));

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
    public void completeWorkorder(UUID workorderId, UUID userId, String completionNotes) {
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new IllegalArgumentException(WORKORDER_NOT_FOUND + workorderId));

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

        // Finalize immutable billable scope snapshot before completion transition
        captureBillableScopeSnapshot(workorder, userId, completionNotes);

        // Set completion fields
        Instant completedAt = Instant.now();
        workorder.setCompletedAt(completedAt);
        workorder.setCompletedBy(userId);
        workorder.setCompletionNotes(completionNotes);
        workorder.setIsReopened(false);
        workorder.setReopenedAt(null);
        workorder.setReopenedBy(null);
        workorder.setReopenReason(null);
        workorderRepository.save(workorder);

        // Transition to COMPLETED state
        transitionWorkorder(workorderId, WorkorderStatus.COMPLETED, userId, "Work Order Completed");

        // Create audit event
        createAuditEvent(workorderId, "StateTransition",
                String.format("{\"fromState\":\"%s\",\"toState\":\"COMPLETED\"}", currentStatus));

        log.info("Workorder {} completed successfully by user {} at {}", workorderId, userId, completedAt);
    }

    @Transactional
    public Workorder reopenCompletedWorkorder(UUID workorderId, UUID userId, String reopenReason) {
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new IllegalArgumentException(WORKORDER_NOT_FOUND + workorderId));

        if (reopenReason == null || reopenReason.isBlank()) {
            throw new IllegalStateException("Reopen reason is required");
        }

        if (workorder.getStatus() != WorkorderStatus.COMPLETED) {
            throw new IllegalStateException(
                    String.format("Workorder %s is not in COMPLETED status and cannot be reopened", workorderId));
        }

        captureSnapshot(workorder, userId, "BILLABLE_SCOPE_SUPERSEDED", reopenReason);

        Instant reopenedAt = Instant.now();
        workorder.setIsReopened(true);
        workorder.setReopenedAt(reopenedAt);
        workorder.setReopenedBy(userId);
        workorder.setReopenReason(reopenReason);

        Workorder saved = workorderRepository.save(workorder);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("state", "COMPLETED");
        auditDetails.put("isReopened", true);
        auditDetails.put("reopenReason", reopenReason);

        String auditDetailsJson;
        try {
            auditDetailsJson = objectMapper.writeValueAsString(auditDetails);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize workorder reopen audit details", e);
        }

        createAuditEvent(workorderId, "WORKORDER_REOPENED", auditDetailsJson);

        log.info("Workorder {} reopened by user {} at {}", workorderId, userId, reopenedAt);
        return saved;
    }

    public void captureBillableScopeSnapshot(Workorder workorder, UUID userId, String completionNotes) {
        boolean invoiceReady = !Boolean.TRUE.equals(workorder.getIsReopened())
                && (workorder.getStatus() == WorkorderStatus.COMPLETED
                        || COMPLETION_ELIGIBLE_STATUSES.contains(workorder.getStatus()));

        WorkorderStatus snapshotStatus = invoiceReady ? WorkorderStatus.COMPLETED : workorder.getStatus();
        Instant workorderCompletedAt = workorder.getCompletedAt() != null ? workorder.getCompletedAt() : Instant.now();

        List<com.positivity.workorder.internal.entity.WorkorderService> services = workorderServiceRepository
                .findByWorkOrder_Id(workorder.getId());

        List<WorkorderPart> parts = loadDeduplicatedWorkorderParts(workorder.getId());

        List<com.positivity.workorder.internal.entity.WorkorderService> billableServices = services.stream()
                .filter(service -> !isExcludedFromBillableTotal(service.getStatus()))
                .toList();

        List<WorkorderPart> billableParts = parts.stream()
                .filter(part -> !isExcludedFromBillableTotal(part.getStatus()))
                .toList();

        BigDecimal serviceTotal = billableServices.stream()
                .map(com.positivity.workorder.internal.entity.WorkorderService::getLineTotal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal partTotal = billableParts.stream()
                .map(WorkorderPart::getLineTotal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Map<String, Object>> billableItems = new ArrayList<>();
        for (com.positivity.workorder.internal.entity.WorkorderService service : billableServices) {
            billableItems.add(buildBillableServiceSnapshotItem(service));
        }
        for (WorkorderPart part : billableParts) {
            billableItems.add(buildBillablePartSnapshotItem(part));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshotVersion", "1.0");
        payload.put("workorderId", workorder.getId());
        payload.put("estimateId", workorder.getEstimateId());
        payload.put("approvalId", workorder.getApprovalId());
        payload.put("customerAccountId", workorder.getCustomerId());
        payload.put("serviceLocationId", workorder.getShopId());
        payload.put("workorderCompletedAt", workorderCompletedAt);
        payload.put("invoiceReady", invoiceReady);
        payload.put(STATUS_FIELD, snapshotStatus);
        payload.put("serviceCount", billableServices.size());
        payload.put("partCount", billableParts.size());
        payload.put("totalServiceCount", services.size());
        payload.put("totalPartCount", parts.size());
        payload.put("lineItems", billableItems);
        payload.put("serviceTotal", serviceTotal);
        payload.put("partTotal", partTotal);
        payload.put("grandTotal", serviceTotal.add(partTotal));
        payload.put("completionNotes", completionNotes);
        payload.put("capturedAt", Instant.now());

        captureStructuredSnapshot(workorder, userId, "BILLABLE_SCOPE_FINALIZED", payload,
                "Final billable scope snapshot before completion");
    }

    public void captureStructuredSnapshot(
            Workorder workorder,
            UUID userId,
            String snapshotType,
            Map<String, Object> snapshotPayload,
            String reason) {
        try {
            String snapshotData = objectMapper.writeValueAsString(snapshotPayload);
            String actorId = resolveActorId(userId);

            WorkorderSnapshot snapshot = WorkorderSnapshot.builder()
                    .workorderId(workorder.getId())
                    .status(workorder.getStatus())
                    .capturedBy(actorId)
                    .snapshotType(snapshotType)
                    .snapshotData(snapshotData)
                    .reason(reason)
                    .capturedAt(Instant.now())
                    .build();

            snapshotRepository.save(snapshot);
            log.info("Captured structured snapshot {} for Workorder {}", snapshotType, workorder.getId());
        } catch (Exception e) {
            log.error("Failed to capture structured snapshot for Workorder {}", workorder.getId(), e);
            throw new IllegalStateException("Failed to capture structured workorder snapshot", e);
        }
    }

    public void captureSnapshot(Workorder workorder, UUID userId, String snapshotType, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workorderId", workorder.getId());
        payload.put("estimateId", workorder.getEstimateId());
        payload.put("customerId", workorder.getCustomerId());
        payload.put(STATUS_FIELD, workorder.getStatus());
        payload.put("capturedAt", Instant.now());
        payload.put("reason", reason);
        captureStructuredSnapshot(workorder, userId, snapshotType, payload, reason);
    }

    private void recordTransition(UUID workorderId, WorkorderStatus fromStatus, WorkorderStatus toStatus,
            UUID userId, String reason) {
        String actorId = resolveActorId(userId);
        WorkorderStateTransition transition = WorkorderStateTransition.builder()
                .workorderId(workorderId)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .transitionedBy(actorId)
                .reason(reason)
                .transitionedAt(Instant.now())
                .build();

        transitionRepository.save(transition);
    }

    private List<WorkorderPart> loadDeduplicatedWorkorderParts(UUID workorderId) {
        Map<UUID, WorkorderPart> partsById = new LinkedHashMap<>();

        for (WorkorderPart part : workorderPartRepository.findByWorkorderId(workorderId)) {
            partsById.putIfAbsent(part.getId(), part);
        }

        for (WorkorderPart part : workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId)) {
            partsById.putIfAbsent(part.getId(), part);
        }

        return new ArrayList<>(partsById.values());
    }

    private Map<String, Object> buildBillableServiceSnapshotItem(
            com.positivity.workorder.internal.entity.WorkorderService service) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemId", service.getId());
        item.put("itemType", "LABOR");
        item.put("sourceSnapshotItemId", service.getOriginEstimateItemId());
        item.put("serviceEntityId", service.getServiceEntityId());
        item.put("description", service.getDescription());
        item.put("quantity", service.getQuantity());
        item.put("unitPrice", service.getUnitPrice());
        item.put("lineTotal", service.getLineTotal());
        item.put("taxCategoryCode", service.getTaxCode());
        item.put("taxable", isTaxable(service.getTaxCode()));
        item.put(STATUS_FIELD, service.getStatus());
        return item;
    }

    private Map<String, Object> buildBillablePartSnapshotItem(WorkorderPart part) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemId", part.getId());
        item.put("itemType", "PART");
        item.put("sourceSnapshotItemId", part.getOriginEstimateItemId());
        item.put("productEntityId", part.getProductEntityId());
        item.put("description", part.getDescription());
        item.put("quantity", part.getQuantity());
        item.put("unitPrice", part.getUnitPrice());
        item.put("lineTotal", part.getLineTotal());
        item.put("taxCategoryCode", part.getTaxCode());
        item.put("taxable", isTaxable(part.getTaxCode()));
        item.put(STATUS_FIELD, part.getStatus());
        return item;
    }

    private boolean isTaxable(String taxCode) {
        return taxCode != null && !taxCode.isBlank();
    }

    private boolean isExcludedFromBillableTotal(WorkorderItemStatus status) {
        return status != null && EXCLUDED_BILLABLE_TOTAL_STATUSES.contains(status);
    }

    public List<WorkorderStateTransition> getTransitionHistory(UUID workorderId) {
        return transitionRepository.findByWorkorderIdOrderByTransitionedAtDesc(workorderId);
    }

    public List<WorkorderSnapshot> getSnapshotHistory(UUID workorderId) {
        return snapshotRepository.findByWorkorderIdOrderByCapturedAtDesc(workorderId);
    }

    private void createAuditEvent(UUID workorderId, String eventType, String details) {
        String actorId = SecurityContextHelper.getCurrentUserIdOrThrowIllegalStateException();
        AuditEvent auditEvent = AuditEvent.builder()
                .entityType("Workorder")
                .entityId(workorderId)
                .eventType(eventType)
                .userId(actorId)
                .details(details)
                .eventTimestamp(Instant.now())
                .build();

        auditEventRepository.save(auditEvent);
        log.debug("Created audit event for Workorder {}: {}", workorderId, eventType);
    }

    public List<AuditEvent> getAuditHistory(UUID workorderId) {
        return auditEventRepository.findByEntityTypeAndEntityIdOrderByEventTimestampDesc("Workorder", workorderId);
    }

    private String resolveActorId(UUID fallbackUserId) {
        String fallback = fallbackUserId != null ? fallbackUserId.toString() : SYSTEM_ACTOR;
        return SecurityContextHelper.getCurrentUserIdOrThrowIllegalStateException();
    }
}
