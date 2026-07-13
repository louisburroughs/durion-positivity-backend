package com.positivity.workorder.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.workorder.internal.client.CustomerValidationClient;
import com.positivity.workorder.internal.client.ShopmgrOperationalContextClient;
import com.positivity.workorder.internal.dto.AssignmentUpdatePayload;
import com.positivity.workorder.internal.dto.AssignmentUpdatedEvent;
import com.positivity.workorder.internal.dto.OperationalContextOverrideRequest;
import com.positivity.workorder.internal.dto.OperationalContextResponse;
import com.positivity.workorder.internal.dto.WorkorderItemCompletionResponse;
import com.positivity.workorder.internal.dto.WorkorderStartResponse;
import com.positivity.workorder.internal.entity.AuditEvent;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.event.EstimateRevisedEvent;
import com.positivity.workorder.internal.event.WorkCompletedEvent;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.service.IdempotencyService;
import com.positivity.workorder.service.PromotionValidationService;
import com.positivity.workorder.service.WorkorderService;
import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkorderServiceImpl implements WorkorderService {

    private static final String SYSTEM_ACTOR = "system";
    private static final String IDEMPOTENCY_OPERATION_WORKORDER_CREATE = "workorder.create";
    private static final String ESTIMATE_PREFIX = "EST-";
    private static final String WORKORDER_PREFIX = "WO-";
    private static final int WORKORDER_NUMBER_SEQUENCE_START = 1000;

    private final Clock clock;
    private final WorkorderRepository workorderRepository;
    private final EstimateRepository estimateRepository;
    private final EstimateItemRepository estimateItemRepository;
    private final WorkorderServiceRepository workorderServiceRepository;
    private final WorkorderPartRepository workorderPartRepository;
    private final CustomerValidationClient customerValidationClient;
    private final WorkorderStateMachine stateMachine;
    private final WorkorderLaborEntryRepository workorderLaborEntryRepository;
    private final org.springframework.context.ApplicationEventPublisher applicationEventPublisher;
    private final AuditEventRepository auditEventRepository;
    private final IdempotencyService idempotencyService;
    private final PromotionValidationService promotionValidationService;
    private final ShopmgrOperationalContextClient shopmgrClient;
    private final PeopleAvailabilityLocalService peopleAvailabilityLocalService;

    @Override
    public List<Workorder> getAllWorkorders() {
        return workorderRepository.findAll();
    }

    @Override
    public Optional<Workorder> getWorkorderById(UUID id) {
        return workorderRepository.findById(id);
    }

    @Override
    @Transactional
    public Workorder createWorkorder(UUID estimateId, UUID customerId) {
        return doCreateWorkorder(estimateId, customerId);
    }

    private Workorder doCreateWorkorder(UUID estimateId, UUID customerId) {
        Estimate estimate = estimateId != null
                ? estimateRepository
                        .findById(estimateId)
                        .orElseThrow(() -> new IllegalArgumentException("Estimate not found: " + estimateId))
                : null;

        if (customerId == null && estimate != null) {
            customerId = estimate.getCustomerId();
            log.debug("Fetched customerId {} from estimate {}", customerId, estimateId);
        }

        // shop_id and location_id are the same concept (a shop is a location); the estimate
        // carries it as location_id. Seed both so the workorder has a shop from creation —
        // the assign page resolves its technician roster from shop_id. Prefer the estimate's
        // location; for estimate-less creation (or a legacy estimate with no location), fall
        // back to the creating user's primary location. Leave null when neither resolves
        // rather than inventing a sentinel shop — the assign page reports "no shop assigned"
        // honestly instead of loading an empty roster for a nonexistent location.
        UUID estimateLocationId = estimate != null ? estimate.getLocationId() : null;
        UUID resolvedLocationId = estimateLocationId != null
                ? estimateLocationId
                : peopleAvailabilityLocalService
                        .resolveCurrentUserPrimaryLocation()
                        .orElse(null);

        Workorder workorder = Workorder.builder()
                .estimate(estimate)
                .workorderNumber(generateWorkorderNumber(estimate))
                .customerId(customerId)
                .shopId(resolvedLocationId)
                .locationId(resolvedLocationId)
                .status(WorkorderStatus.DRAFT)
                .crmPartyId(estimate != null ? estimate.getCrmPartyId() : null)
                .crmVehicleId(estimate != null ? estimate.getCrmVehicleId() : null)
                .crmContactIds(
                        estimate != null && estimate.getCrmContactIds() != null
                                ? new ArrayList<>(estimate.getCrmContactIds())
                                : new ArrayList<>())
                .build();
        return createWorkorderInternal(workorder);
    }

    /**
     * Generate the human workorder number. When created from an estimate, swap the
     * estimate's prefix (EST-... -> WO-...) if that value is globally free; otherwise
     * fall back to an independent WO-YYYY-NNNN sequence. This keeps the workorder number
     * "matching the estimate number except the prefix" for the common 1:1 case while
     * guaranteeing global uniqueness for estimate-less or revision cases.
     */
    @NonNull
    private String generateWorkorderNumber(@Nullable Estimate estimate) {
        if (estimate != null
                && estimate.getEstimateNumber() != null
                && estimate.getEstimateNumber().startsWith(ESTIMATE_PREFIX)) {
            String swapped = WORKORDER_PREFIX + estimate.getEstimateNumber().substring(ESTIMATE_PREFIX.length());
            if (!workorderRepository.existsByWorkorderNumber(swapped)) {
                return swapped;
            }
            log.debug("Workorder number {} already taken; falling back to sequence", swapped);
        }
        return generateSequentialWorkorderNumber();
    }

    @NonNull
    private String generateSequentialWorkorderNumber() {
        String prefix = WORKORDER_PREFIX + Year.now(clock).getValue() + "-";
        int sequence = WORKORDER_NUMBER_SEQUENCE_START;
        String candidate;
        do {
            candidate = prefix + sequence;
            sequence++;
        } while (workorderRepository.existsByWorkorderNumber(candidate));
        return candidate;
    }

    /**
     * Create a workorder with idempotency key support.
     *
     * <p>
     * If an idempotency key is provided and has been processed before,
     * returns the existing workorder instead of creating a duplicate.
     * </p>
     *
     * @param estimateId     the estimate ID
     * @param customerId     the customer ID
     * @param idempotencyKey optional idempotency key for duplicate prevention; if
     *                       null, idempotency is not enforced
     * @return the created or existing workorder
     */
    @Override
    @Transactional
    public Workorder createWorkorderWithIdempotency(UUID estimateId, UUID customerId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreateWorkorder(estimateId, customerId);
        }

        Optional<Workorder> existingWorkorder = getExistingIdempotentWorkorder(idempotencyKey);
        if (existingWorkorder.isPresent()) {
            return existingWorkorder.get();
        }

        Workorder created = doCreateWorkorder(estimateId, customerId);

        return registerIdempotencyKeyWithFallback(idempotencyKey, created);
    }

    private Optional<Workorder> getExistingIdempotentWorkorder(String idempotencyKey) {
        Optional<UUID> existingWorkorderId =
                idempotencyService.getExistingWorkorderId(IDEMPOTENCY_OPERATION_WORKORDER_CREATE, idempotencyKey);
        if (existingWorkorderId.isPresent()) {
            log.info(
                    "Idempotent request detected for key {}; returning existing workorder {}",
                    idempotencyKey,
                    existingWorkorderId.get());
            Workorder workorder = workorderRepository
                    .findById(existingWorkorderId.get())
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency key points to non-existent workorder: " + existingWorkorderId.get()));
            return Optional.of(workorder);
        }
        return Optional.empty();
    }

    private Workorder registerIdempotencyKeyWithFallback(String idempotencyKey, Workorder created) {
        try {
            idempotencyService.registerKey(IDEMPOTENCY_OPERATION_WORKORDER_CREATE, idempotencyKey, created.getId());
            // Force flush so any unique-constraint violation is raised within this
            // try/catch
            TransactionAspectSupport.currentTransactionStatus().flush();
            return created;
        } catch (DataIntegrityViolationException _) {
            // Race condition: another request already registered this key
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

            return getExistingIdempotentWorkorder(idempotencyKey).orElseThrow(() -> {
                log.error(
                        "Race condition detected but no existing workorder found for idempotency key {}",
                        idempotencyKey);
                return new IllegalStateException(
                        "DataIntegrityViolationException occurred but no workorder found for key: " + idempotencyKey);
            });
        }
    }

    @Override
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

            log.info("Promotion preconditions validated successfully for estimate {}", workorder.getEstimateId());
        }

        // If approvalId is set, the workorder must already carry a valid customer
        // approval (status APPROVED with an approvedAt timestamp), as stamped by
        // approveWorkorder() - this is an internal Workorder state check.
        if (workorder.getApprovalId() != null && !isCustomerApproved(workorder)) {
            throw new IllegalArgumentException("Customer approval not found or not valid");
        }

        // Save the workorder first to get the persisted entity
        Workorder savedWorkorder = workorderRepository.save(workorder);

        // CAP:004 Story #27 - Copy estimate items to workorder items if promoting from
        // estimate
        if (savedWorkorder.getEstimateId() != null) {
            log.info(
                    "Copying estimate items to workorder items for estimate {} and workorder {}",
                    savedWorkorder.getEstimateId(),
                    savedWorkorder.getId());
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
        List<EstimateItem> estimateItems = estimateItemRepository.findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                estimateId, ApprovalStatus.APPROVED);

        if (estimateItems.isEmpty()) {
            log.warn("No approved estimate items found for estimate {}, no workorder items created", estimateId);
            return;
        }

        log.info("Found {} approved estimate items to copy to workorder {}", estimateItems.size(), workorder.getId());

        List<com.positivity.workorder.internal.entity.WorkorderServiceLine> laborItems = new ArrayList<>();
        List<WorkorderPart> partItems = new ArrayList<>();

        for (EstimateItem estimateItem : estimateItems) {
            if (estimateItem.getItemType() == EstimateItemType.LABOR) {
                // Create WorkorderServiceLine for LABOR items
                com.positivity.workorder.internal.entity.WorkorderServiceLine workorderService =
                        com.positivity.workorder.internal.entity.WorkorderServiceLine.builder()
                                .workOrder(workorder)
                                .description(estimateItem.getDescription())
                                .quantity(estimateItem.getQuantity())
                                .unitPrice(estimateItem.getUnitPrice())
                                .lineTotal(estimateItem.getLineTotal())
                                .taxCode(estimateItem.getTaxCode())
                                .originEstimateItem(estimateItem)
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
                        .originEstimateItem(estimateItem)
                        .productEntityId(estimateItem.getProductId())
                        .status(WorkorderItemStatus.OPEN) // Initial status: OPEN (Authorized in contract)
                        .declined(false)
                        .isEmergencySafety(false)
                        .photoNotPossible(false)
                        .build();

                partItems.add(workorderPart);
            } else {
                log.warn(
                        "Unsupported EstimateItemType {} for estimate item {}, skipping",
                        estimateItem.getItemType(),
                        estimateItem.getId());
            }
        }

        // Persist all labor items
        if (!laborItems.isEmpty()) {
            workorderServiceRepository.saveAll(laborItems);
            log.info("Persisted {} labor items for workorder {}", laborItems.size(), workorder.getId());
            laborItems.forEach(item -> log.debug(
                    "Created workorder service item with ID {}: from estimate item {}",
                    item.getId(),
                    item.getOriginEstimateItemId()));
        }

        // Persist all part items
        if (!partItems.isEmpty()) {
            workorderPartRepository.saveAll(partItems);
            log.info("Persisted {} part items for workorder {}", partItems.size(), workorder.getId());
            partItems.forEach(item -> log.debug(
                    "Created workorder part item with ID {}: from estimate item {}",
                    item.getId(),
                    item.getOriginEstimateItemId()));
        }

        log.info(
                "Successfully copied {} estimate items to workorder {} ({} labor, {} parts)",
                estimateItems.size(),
                workorder.getId(),
                laborItems.size(),
                partItems.size());
    }

    @Override
    public void deleteWorkorder(UUID id) {
        workorderRepository.deleteById(id);
    }

    private boolean checkCustomerRequirements(UUID customerId) {
        return customerValidationClient.checkRequirementsMet(customerId);
    }

    private boolean isCustomerApproved(Workorder workorder) {
        return workorder.getStatus() == WorkorderStatus.APPROVED && workorder.getApprovedAt() != null;
    }

    @Override
    @Transactional
    public void startWorkorder(UUID workorderId, String actorId, String reason) {
        stateMachine.startWorkorder(workorderId, actorId, reason);
    }

    @Override
    @Transactional
    public Workorder approveWorkorder(
            UUID workorderId,
            UUID customerId,
            String signatureData,
            String signatureMimeType,
            String signerName,
            String notes) {
        Workorder workorder = workorderRepository
                .findById(workorderId)
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
        workorder.setApprovedAt(Instant.now(clock));
        workorder.setApprovalId(customerId);
        workorder.setSignatureData(signatureData);
        workorder.setSignatureMimeType(signatureMimeType);
        workorder.setSignerName(signerName);
        workorder.setApprovalNotes(notes);

        log.info("Workorder {} approved by customer {} with signature capture", workorderId, customerId);
        return workorderRepository.save(workorder);
    }

    @Override
    @Transactional
    public void transitionWorkorder(UUID workorderId, WorkorderStatus toStatus, String actorId, String reason) {
        stateMachine.transitionWorkorder(workorderId, toStatus, actorId, reason);
    }

    @Override
    public List<com.positivity.workorder.internal.entity.WorkorderStateTransition> getTransitionHistory(
            UUID workorderId) {
        return stateMachine.getTransitionHistory(workorderId);
    }

    @Override
    public List<com.positivity.workorder.internal.entity.WorkorderSnapshot> getSnapshotHistory(UUID workorderId) {
        return stateMachine.getSnapshotHistory(workorderId);
    }

    @Override
    public WorkorderStateMachine.CompletionPreconditions getCompletionPreconditions(UUID workorderId) {
        return stateMachine.evaluateCompletionPreconditions(workorderId);
    }

    @Override
    public String getCurrentWorkorderStatus(UUID workorderId) {
        return workorderRepository
                .findById(workorderId)
                .map(workorder -> workorder.getStatus().name())
                .orElseThrow(() -> new IllegalArgumentException("Workorder not found: " + workorderId));
    }

    @Override
    public Instant getCompletedAt(UUID workorderId) {
        return workorderRepository
                .findById(workorderId)
                .map(Workorder::getCompletedAt)
                .orElse(null);
    }

    @Override
    @Transactional
    public WorkorderItemCompletionResponse completeServiceItem(UUID workorderId, UUID serviceLineId, String actorId) {
        WorkorderServiceLine line = workorderServiceRepository
                .findById(serviceLineId)
                .orElseThrow(() -> new IllegalArgumentException("Service line not found: " + serviceLineId));
        if (line.getWorkOrder() == null
                || !workorderId.equals(line.getWorkOrder().getId())) {
            throw new IllegalArgumentException(
                    "Service line " + serviceLineId + " does not belong to workorder " + workorderId);
        }
        WorkorderItemStatus status = completeItemStatus(line.getStatus(), "service line", serviceLineId);
        if (status != line.getStatus()) {
            line.setStatus(status);
            workorderServiceRepository.save(line);
            log.info("Service line {} on workorder {} marked COMPLETED by {}", serviceLineId, workorderId, actorId);
        }
        return WorkorderItemCompletionResponse.builder()
                .workorderId(workorderId)
                .itemId(serviceLineId)
                .itemType(WorkorderItemCompletionResponse.ItemType.SERVICE)
                .status(status)
                .build();
    }

    @Override
    @Transactional
    public WorkorderItemCompletionResponse completePartItem(UUID workorderId, UUID partId, String actorId) {
        WorkorderPart part = workorderPartRepository
                .findById(partId)
                .orElseThrow(() -> new IllegalArgumentException("Part not found: " + partId));
        if (!partBelongsToWorkorder(part, workorderId)) {
            throw new IllegalArgumentException("Part " + partId + " does not belong to workorder " + workorderId);
        }
        WorkorderItemStatus status = completeItemStatus(part.getStatus(), "part", partId);
        if (status != part.getStatus()) {
            part.setStatus(status);
            workorderPartRepository.save(part);
            log.info("Part {} on workorder {} marked COMPLETED by {}", partId, workorderId, actorId);
        }
        return WorkorderItemCompletionResponse.builder()
                .workorderId(workorderId)
                .itemId(partId)
                .itemType(WorkorderItemCompletionResponse.ItemType.PART)
                .status(status)
                .build();
    }

    /**
     * Validate an item is completable and return COMPLETED. Idempotent when already
     * COMPLETED; rejects CANCELLED and PENDING_APPROVAL (unapproved) items.
     */
    private WorkorderItemStatus completeItemStatus(@Nullable WorkorderItemStatus current, String label, UUID itemId) {
        if (current == WorkorderItemStatus.COMPLETED) {
            return WorkorderItemStatus.COMPLETED;
        }
        if (current == WorkorderItemStatus.CANCELLED || current == WorkorderItemStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Cannot complete " + label + " " + itemId + " in status " + current);
        }
        return WorkorderItemStatus.COMPLETED;
    }

    private boolean partBelongsToWorkorder(WorkorderPart part, UUID workorderId) {
        if (part.getWorkorder() != null
                && workorderId.equals(part.getWorkorder().getId())) {
            return true;
        }
        return part.getWorkOrderService() != null
                && part.getWorkOrderService().getWorkOrder() != null
                && workorderId.equals(part.getWorkOrderService().getWorkOrder().getId());
    }

    @Override
    @Transactional
    public WorkCompletedEvent completeWorkorder(UUID workorderId, String actorId, String completionNotes) {
        // Perform the completion logic
        stateMachine.completeWorkorder(workorderId, actorId, completionNotes);

        // Retrieve the updated work order
        Workorder workorder = workorderRepository
                .findById(workorderId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Workorder not found after completion: " + workorderId));

        publishJobTimeFacts(workorder);

        // Build the final billable scope
        Map<String, Object> finalBillableScope = buildFinalBillableScope(workorder);

        // Create and return the event
        String eventId = UUIDv7Generator.generate().toString();
        String idempotencyKey = String.format(
                "%s:completion_%s", workorderId, workorder.getCompletedAt().toEpochMilli());

        WorkCompletedEvent.WorkCompletedPayload payload = WorkCompletedEvent.WorkCompletedPayload.builder()
                .workorderId(workorderId)
                .completedAt(workorder.getCompletedAt())
                .completedBy(workorder.getCompletedBy())
                .finalBillableScope(finalBillableScope)
                .build();

        WorkCompletedEvent event = WorkCompletedEvent.builder()
                .eventId(eventId)
                .eventType("WorkCompleted")
                .eventTimestamp(Instant.now(clock))
                .sourceDomain("workexec")
                .idempotencyKey(idempotencyKey)
                .payload(payload)
                .build();

        log.info("WorkCompleted event created with eventId={} for workorderId={}", eventId, workorderId);
        return event;
    }

    /**
     * One {@code workorder.job_time.recorded.v1} fact per finalized labor entry (ADR-0044 §6,
     * #875): pos-people replaces its synchronous job-time-totals lookup with a replica fed by
     * these facts. Published inside the completion transaction; the Kafka relay writes them to
     * the outbox BEFORE_COMMIT, so facts exist iff the completion committed. Re-completions
     * re-emit; consumers upsert by laborEntryId.
     */
    private void publishJobTimeFacts(Workorder workorder) {
        for (WorkorderLaborEntry entry :
                workorderLaborEntryRepository.findByWorkorder_IdOrderByStartTimeDesc(workorder.getId())) {
            if (entry.getEndTime() == null || entry.getHoursWorked() == null || entry.getTechnicianId() == null) {
                continue;
            }
            int minutes = entry.getHoursWorked()
                    .multiply(java.math.BigDecimal.valueOf(60))
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .intValue();
            applicationEventPublisher.publishEvent(new com.positivity.domainevents.workorder.JobTimeRecordedV1(
                    entry.getId(),
                    workorder.getId(),
                    entry.getTechnicianId(),
                    workorder.getShopId(),
                    entry.getEndTime().atOffset(java.time.ZoneOffset.UTC).toInstant(),
                    minutes));
        }
    }

    @Override
    @Transactional
    public WorkorderService.ReopenResult reopenCompletedWorkorder(
            UUID workorderId, String actorId, String reopenReason) {
        Workorder reopened = stateMachine.reopenCompletedWorkorder(workorderId, actorId, reopenReason);
        return new WorkorderService.ReopenResult(
                reopened.getId(), reopened.getStatus().name(), reopened.getIsReopened(), reopened.getReopenedAt());
    }

    private Map<String, Object> buildFinalBillableScope(Workorder workorder) {
        Map<String, Object> scope = new HashMap<>();
        scope.put("workorderId", workorder.getId());
        scope.put(
                "services",
                workorder.getServices() != null ? workorder.getServices().size() : 0);
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
    @Override
    @EventListener
    @Transactional
    public void onEstimateRevised(EstimateRevisedEvent event) {
        log.info(
                "Received EstimateRevisedEvent: estimateId={}, workorderId={}, " + "oldTotal={}, newTotal={}",
                event.getEstimateId(),
                event.getWorkorderId(),
                event.getOldTotal(),
                event.getNewTotal());

        Optional<Workorder> workorderOpt = workorderRepository.findById(event.getWorkorderId());

        if (workorderOpt.isEmpty()) {
            log.warn("Workorder {} not found for EstimateRevisedEvent", event.getWorkorderId());
            return;
        }

        Workorder workorder = workorderOpt.get();

        // Only invalidate approval if Workorder is currently in APPROVED status
        if (workorder.getStatus() != WorkorderStatus.APPROVED) {
            log.info(
                    "Workorder {} not in APPROVED status (current: {}), skipping invalidation",
                    workorder.getId(),
                    workorder.getStatus());
            return;
        }

        // Capture old status for audit
        WorkorderStatus oldStatus = workorder.getStatus();

        // Transition to AWAITING_APPROVAL (re-approval required)
        workorder.setStatus(WorkorderStatus.AWAITING_APPROVAL);
        workorderRepository.save(workorder);

        // Create audit event for traceability
        createApprovalInvalidationAudit(workorder, oldStatus, event);

        log.info(
                "Workorder {} approval invalidated due to estimate revision. "
                        + "Status changed from {} to {}. Old total: {}, New total: {}",
                workorder.getId(),
                oldStatus,
                WorkorderStatus.AWAITING_APPROVAL,
                event.getOldTotal(),
                event.getNewTotal());
    }

    /**
     * Create audit event for approval invalidation.
     * This provides traceability for compliance and debugging.
     */
    private void createApprovalInvalidationAudit(
            Workorder workorder, WorkorderStatus oldStatus, EstimateRevisedEvent event) {
        String actorId = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR);
        AuditEvent audit = AuditEvent.builder()
                .entityType("Workorder")
                .entityId(workorder.getId())
                .eventType("approval.invalidated")
                .eventTimestamp(event.getTimestamp())
                .userId(actorId)
                .details(String.format(
                        "Approval invalidated due to estimate revision. " + "Previous status: %s, New status: %s, "
                                + "EstimateId: %s, Old total: %s, New total: %s, Change: %s",
                        oldStatus.name(),
                        WorkorderStatus.AWAITING_APPROVAL.name(),
                        event.getEstimateId(),
                        event.getOldTotal(),
                        event.getNewTotal(),
                        event.getChangeAmount()))
                .build();

        auditEventRepository.save(audit);

        log.info(
                "Created audit event for approval invalidation: workorderId={}, " + "estimateId={}",
                workorder.getId(),
                event.getEstimateId());
    }

    @Override
    @Transactional
    public void handleAssignmentUpdated(@NonNull AssignmentUpdatedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }

        Workorder workorder = workorderRepository
                .findById(event.getWorkorderId())
                .orElseThrow(() -> new WorkorderNotFoundException(event.getWorkorderId()));

        WorkorderStatus status = workorder.getStatus();
        if (status != WorkorderStatus.DRAFT
                && status != WorkorderStatus.APPROVED
                && status != WorkorderStatus.ASSIGNED) {
            log.warn(
                    "Skipping assignment context update for workorder {} in non-updatable status {}",
                    event.getWorkorderId(),
                    status);
            return;
        }

        String oldLocationId =
                workorder.getLocationId() != null ? workorder.getLocationId().toString() : null;
        String oldResourceId =
                workorder.getResourceId() != null ? workorder.getResourceId().toString() : null;
        String oldMechanicIds = workorder.getMechanicIds();

        AssignmentUpdatePayload payload = event.getPayload();
        workorder.setLocationId(payload.getLocationId());
        workorder.setResourceId(payload.getResourceId());
        workorder.setMechanicIds(serializeMechanicIds(payload.getMechanicIds()));
        workorderRepository.save(workorder);

        String details = buildAuditDetails(
                oldLocationId,
                oldResourceId,
                oldMechanicIds,
                payload.getLocationId(),
                payload.getResourceId(),
                payload.getMechanicIds());

        AuditEvent auditEvent = AuditEvent.builder()
                .entityType("Workorder")
                .entityId(workorder.getId())
                .eventType("AssignmentContextUpdated")
                .userId("System:ShopManagementService")
                .details(details)
                .eventTimestamp(Instant.now(clock))
                .build();
        auditEventRepository.save(auditEvent);

        log.info(
                "Assignment context updated for workorder {}: locationId={}, resourceId={}, mechanicCount={}",
                workorder.getId(),
                payload.getLocationId(),
                payload.getResourceId(),
                payload.getMechanicIds() != null ? payload.getMechanicIds().size() : 0);
    }

    @Override
    public OperationalContextResponse getOperationalContext(@NonNull UUID workorderId) {
        Workorder workorder = workorderRepository
                .findById(workorderId)
                .orElseThrow(() -> new WorkorderNotFoundException(workorderId));
        log.debug("Fetching operational context for existing workorder {}", workorder.getId());
        return shopmgrClient.getOperationalContext(workorderId);
    }

    @Override
    public OperationalContextResponse overrideOperationalContext(
            @NonNull UUID workorderId, @NonNull OperationalContextOverrideRequest override) {
        Workorder workorder = workorderRepository
                .findById(workorderId)
                .orElseThrow(() -> new WorkorderNotFoundException(workorderId));

        if (workorder.getWorkStartedAt() != null) {
            throw new IllegalStateException("Work has already started; operational context cannot be overridden");
        }

        workorder.setLocationId(override.getLocationId());
        List<UUID> assignedResources = override.getAssignedResources();
        workorder.setResourceId(
                assignedResources != null && !assignedResources.isEmpty() ? assignedResources.get(0) : null);
        workorder.setMechanicIds(serializeMechanicIds(override.getAssignedMechanics()));
        Workorder saved = workorderRepository.save(workorder);

        return OperationalContextResponse.builder()
                .version(saved.getOperationalContextVersion())
                .locationId(saved.getLocationId())
                .bayId(override.getBayId())
                .assignedMechanics(parseMechanicIds(saved.getMechanicIds()))
                .assignedResources(
                        saved.getResourceId() == null ? Collections.emptyList() : List.of(saved.getResourceId()))
                .constraints(override.getConstraints())
                .locked(saved.getWorkStartedAt() != null)
                .build();
    }

    @Override
    public WorkorderStartResponse startWork(@NonNull UUID workorderId) {
        return startWork(workorderId, null, null);
    }

    @Override
    public WorkorderStartResponse startWork(@NonNull UUID workorderId, String requestedUserId, String reason) {
        Workorder workorder = workorderRepository
                .findById(workorderId)
                .orElseThrow(() -> new WorkorderNotFoundException(workorderId));

        if (workorder.getWorkStartedAt() != null) {
            throw new IllegalStateException("Work has already started for workorder: " + workorderId);
        }

        WorkorderStatus previousStatus = workorder.getStatus();
        String actorUserId = requestedUserId != null ? requestedUserId : resolveCurrentActorUserId();
        String transitionReason = reason != null && !reason.isBlank() ? reason : "Work started";

        stateMachine.startWorkorder(workorderId, actorUserId, transitionReason);

        String version = UUIDv7Generator.generate().toString();
        Instant startedAt = Instant.now(clock);
        workorder.setOperationalContextVersion(version);
        workorder.setWorkStartedAt(startedAt);
        Workorder saved = workorderRepository.save(workorder);

        Instant transitionedAt = stateMachine.getTransitionHistory(workorderId).stream()
                .filter(transition -> transition.getToStatus() == WorkorderStatus.WORK_IN_PROGRESS)
                .map(WorkorderStateTransition::getTransitionedAt)
                .findFirst()
                .orElse(startedAt);

        return WorkorderStartResponse.builder()
                .workorderId(saved.getId())
                .operationalContextVersion(saved.getOperationalContextVersion())
                .workStartedAt(saved.getWorkStartedAt())
                .previousStatus(previousStatus.name())
                .currentStatus(WorkorderStatus.WORK_IN_PROGRESS.name())
                .transitionedAt(transitionedAt)
                .message("Workorder started successfully")
                .build();
    }

    private String resolveCurrentActorUserId() {
        return SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR);
    }

    private String serializeMechanicIds(List<UUID> mechanicIds) {
        if (mechanicIds == null || mechanicIds.isEmpty()) {
            return "[]";
        }
        return mechanicIds.stream()
                .map(uuid -> "\"" + uuid + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private List<UUID> parseMechanicIds(String mechanicIds) {
        if (mechanicIds == null || mechanicIds.isBlank() || "[]".equals(mechanicIds)) {
            return Collections.emptyList();
        }
        return java.util.Arrays.stream(
                        mechanicIds.replace("[", "").replace("]", "").split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.replace("\"", ""))
                .filter(value -> !value.isBlank())
                .map(UUID::fromString)
                .toList();
    }

    private String buildAuditDetails(
            String oldLocationId,
            String oldResourceId,
            String oldMechanicIds,
            UUID newLocationId,
            UUID newResourceId,
            List<UUID> newMechanicIds) {
        String oldLocJson = oldLocationId != null ? "\"" + oldLocationId + "\"" : "null";
        String oldResJson = oldResourceId != null ? "\"" + oldResourceId + "\"" : "null";
        String oldMechJson = oldMechanicIds != null ? oldMechanicIds : "[]";
        String newLocJson = newLocationId != null ? "\"" + newLocationId + "\"" : "null";
        String newResJson = newResourceId != null ? "\"" + newResourceId + "\"" : "null";
        String newMechJson = serializeMechanicIds(newMechanicIds);
        return String.format(
                "{\"oldLocationId\":%s,\"oldResourceId\":%s,\"oldMechanicIds\":%s,"
                        + "\"newLocationId\":%s,\"newResourceId\":%s,\"newMechanicIds\":%s}",
                oldLocJson, oldResJson, oldMechJson, newLocJson, newResJson, newMechJson);
    }
}
