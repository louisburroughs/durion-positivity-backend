package com.positivity.inventory.internal.cyclecount.service;

import com.positivity.inventory.internal.dto.cyclecount.AdjustmentResponse;
import com.positivity.inventory.internal.dto.cyclecount.ApproveAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.CreateAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.RejectAdjustmentRequest;
import com.positivity.inventory.internal.entity.CycleCountAdjustment;
import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.TaskStatus;
import com.positivity.inventory.internal.event.AuditActorRef;
import com.positivity.inventory.internal.event.AuditAggregateRef;
import com.positivity.inventory.internal.event.InventoryAuditEvent;
import com.positivity.inventory.internal.exception.AdjustmentLedgerPostingException;
import com.positivity.inventory.internal.exception.CycleCountConflictException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.internal.repository.CycleCountTaskRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import com.positivity.inventory.internal.service.ApprovalThresholdEvaluator;
import com.positivity.inventory.internal.service.BaseUnitOfMeasureResolver;
import com.positivity.inventory.internal.service.CostingMethodResolver;
import com.positivity.inventory.internal.service.CycleCountConflictDetector;
import com.positivity.inventory.internal.service.LedgerPostingService;
import com.positivity.inventory.internal.service.Quantities;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation for cycle count adjustment operations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CycleCountAdjustmentServiceImpl implements CycleCountAdjustmentService {

    private static final String ADJUSTMENT_NOT_FOUND = "Adjustment not found: ";

    private final CycleCountAdjustmentRepository adjustmentRepository;
    private final InventoryLedgerEntryRepository ledgerRepository;
    private final LedgerPostingService ledgerPostingService;
    private final ApprovalThresholdEvaluator thresholdEvaluator;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final CycleCountTaskRepository taskRepository;
    private final CycleCountConflictDetector conflictDetector;
    private final SkuCostStateRepository costStateRepository;
    private final CostingMethodResolver methodResolver;
    private final BaseUnitOfMeasureResolver baseUnitOfMeasureResolver;

    @Override
    @Transactional
    public AdjustmentResponse createAdjustment(CreateAdjustmentRequest request) {
        log.info(
                "Creating cycle count adjustment for SKU {} by user {}",
                request.getStockItemId(),
                request.getCreatedByUserId());

        BigDecimal quantityChange = request.getCountedQuantity().subtract(request.getQuantityOnHandBefore());
        if (quantityChange.signum() == 0) {
            log.info("No variance detected for SKU {}. Count matches system quantity.", request.getStockItemId());
            throw new IllegalArgumentException("No adjustment needed - counted quantity matches system quantity");
        }

        if (request.getTaskId() != null && !taskRepository.existsById(request.getTaskId())) {
            throw new TaskNotFoundException(request.getTaskId());
        }

        // odoo-parity J3 (#1053): source costAtTimeOfAdjustment from the J1 costing engine's
        // per-SKU running cost (ADR-0048) instead of the interim client/latest-receipt source.
        // The engine value is authoritative when present; a request-supplied cost is the fallback
        // for a SKU the engine has not yet costed (no sku_cost_state row / null running cost).
        BigDecimal engineCost = resolveEngineCost(request.getStockItemId());
        BigDecimal costAtTimeOfAdjustment = engineCost != null ? engineCost : request.getCostAtTimeOfAdjustment();

        CycleCountAdjustment adjustment = CycleCountAdjustment.builder()
                .stockItemId(request.getStockItemId())
                .taskId(request.getTaskId())
                .reasonCode(request.getReasonCode())
                .quantityChange(quantityChange)
                .costAtTimeOfAdjustment(costAtTimeOfAdjustment)
                .quantityOnHandBefore(request.getQuantityOnHandBefore())
                .countedQuantity(request.getCountedQuantity())
                .createdByUserId(request.getCreatedByUserId())
                .status(AdjustmentStatus.PENDING_APPROVAL)
                .build();

        Optional<ApprovalTier> requiredTier = thresholdEvaluator.evaluateRequiredApprovalTier(adjustment);
        if (requiredTier.isPresent()) {
            adjustment.setRequiredApprovalTier(requiredTier.get());
            adjustment.setStatus(AdjustmentStatus.PENDING_APPROVAL);
            adjustment = adjustmentRepository.save(adjustment);

            log.info(
                    "Adjustment {} created with status PENDING_APPROVAL (tier: {})",
                    adjustment.getAdjustmentId(),
                    requiredTier.get());
        } else {
            adjustment.setStatus(AdjustmentStatus.AUTO_APPROVED);
            adjustment.setApprovedByUserId("SYSTEM");
            adjustment.setApprovedAt(Instant.now(clock));
            adjustment = adjustmentRepository.save(adjustment);

            log.info("Adjustment {} auto-approved (below thresholds)", adjustment.getAdjustmentId());
            // Auto-approval is still an approval: the conflict gate applies
            // (odoo-parity I2, #1026) before anything posts to the ledger.
            guardConflictAndRecompute(adjustment);
            postApprovedAdjustment(adjustment);
        }

        return toResponse(adjustment);
    }

    @Override
    @Transactional
    public AdjustmentResponse approveAdjustment(
            @NonNull UUID adjustmentId, @NonNull ApproveAdjustmentRequest request, @Nullable String correlationId) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException("No current user"));
        String actorUsername = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        log.info("Approving adjustment {} by authenticated actor {}", adjustmentId, actorUsername);

        CycleCountAdjustment adjustment = adjustmentRepository
                .findById(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException(ADJUSTMENT_NOT_FOUND + adjustmentId));

        if (adjustment.getStatus() != AdjustmentStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Cannot approve adjustment in status: " + adjustment.getStatus());
        }

        // Conflict gate (odoo-parity I2, #1026): a first approval attempt that
        // detects in-window movements flags the task CONFLICT and aborts;
        // approving a CONFLICT task recomputes the variance against CURRENT
        // on-hand — never the stale snapshot.
        guardConflictAndRecompute(adjustment);

        Instant occurredAt = Instant.now(clock);
        adjustment.setStatus(AdjustmentStatus.APPROVED);
        adjustment.setApprovedByUserId(actorUserId);
        adjustment.setApprovedAt(occurredAt);
        adjustment = adjustmentRepository.save(adjustment);

        log.info("Adjustment {} approved by {} ({})", adjustmentId, actorUsername, actorUserId);
        postApprovedAdjustment(adjustment);
        publishMovementAdjustedEvent(adjustment, occurredAt, actorUserId, actorUsername, correlationId);

        return toResponse(adjustment);
    }

    @Override
    @Transactional
    public AdjustmentResponse rejectAdjustment(UUID adjustmentId, RejectAdjustmentRequest request) {
        log.info(
                "Rejecting adjustment {} by user {}: {}",
                adjustmentId,
                request.getRejectorUserId(),
                request.getRejectionReason());

        CycleCountAdjustment adjustment = adjustmentRepository
                .findById(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException(ADJUSTMENT_NOT_FOUND + adjustmentId));

        if (adjustment.getStatus() != AdjustmentStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Cannot reject adjustment in status: " + adjustment.getStatus());
        }

        adjustment.setStatus(AdjustmentStatus.REJECTED);
        adjustment.setRejectedByUserId(request.getRejectorUserId());
        adjustment.setRejectionReason(request.getRejectionReason());
        adjustment.setRejectedAt(Instant.now(clock));
        adjustment = adjustmentRepository.save(adjustment);

        log.info("Adjustment {} rejected by {}", adjustmentId, request.getRejectorUserId());
        return toResponse(adjustment);
    }

    @Override
    @Transactional(readOnly = true)
    public AdjustmentResponse getAdjustment(UUID adjustmentId) {
        CycleCountAdjustment adjustment = adjustmentRepository
                .findById(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException(ADJUSTMENT_NOT_FOUND + adjustmentId));
        return toResponse(adjustment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdjustmentResponse> listAdjustmentsByStatus(AdjustmentStatus status) {
        return adjustmentRepository.findByStatus(status).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countAdjustmentsByStatus(AdjustmentStatus status) {
        return adjustmentRepository.countByStatus(status);
    }

    /**
     * Conflict gate applied at every approval (odoo-parity I2, #1026), for
     * adjustments linked to a cycle-count task:
     * <ul>
     *   <li>task already {@code CONFLICT} — the reviewer's approval IS the
     *       explicit "accept" choice, so the variance is recomputed against
     *       CURRENT on-hand (never the stale snapshot) before posting;</li>
     *   <li>task not yet flagged but in-window movements exist — the task is
     *       flagged {@code CONFLICT} (in its own transaction, surviving this
     *       rollback) and the approval is rejected so the reviewer must choose
     *       explicitly: recount, or re-approve with recomputation.</li>
     * </ul>
     */
    private void guardConflictAndRecompute(CycleCountAdjustment adjustment) {
        if (adjustment.getTaskId() == null) {
            return;
        }
        CycleCountTask task = taskRepository
                .findById(adjustment.getTaskId())
                .orElseThrow(() -> new TaskNotFoundException(adjustment.getTaskId()));

        if (task.getStatus() == TaskStatus.CONFLICT) {
            recomputeVarianceAgainstCurrentOnHand(adjustment, task);
            return;
        }

        BigDecimal movementDelta = conflictDetector.movementDeltaSinceSnapshot(task);
        if (!Quantities.isZero(movementDelta)) {
            conflictDetector.flagConflict(task.getTaskId());
            throw new CycleCountConflictException(task.getTaskId(), movementDelta);
        }
    }

    /**
     * Replaces the stale snapshot math with current-on-hand math: quantityChange
     * becomes countedQuantity - currentOnHand, using the same on-hand
     * aggregation the posting path uses for quantityAfter — scoped to the
     * task's storage location when its bin holds a location UUID (a count of
     * bin A must never be reconciled against the SKU's stock in bins B and C),
     * global otherwise. The funnel's floor-at-zero matrix still applies when
     * the recomputed variance posts.
     */
    private void recomputeVarianceAgainstCurrentOnHand(CycleCountAdjustment adjustment, CycleCountTask task) {
        BigDecimal currentOnHand = currentOnHand(
                adjustment.getStockItemId(),
                CycleCountConflictDetector.locationIdOf(task).orElse(null));
        BigDecimal recomputedChange = adjustment.getCountedQuantity().subtract(currentOnHand);
        log.info(
                "Adjustment {} approved on CONFLICT task {}: variance recomputed against current on-hand"
                        + " {} (stale snapshot {}): quantityChange {} -> {}",
                adjustment.getAdjustmentId(),
                adjustment.getTaskId(),
                currentOnHand,
                adjustment.getQuantityOnHandBefore(),
                adjustment.getQuantityChange(),
                recomputedChange);
        adjustment.setQuantityOnHandBefore(currentOnHand);
        adjustment.setQuantityChange(recomputedChange);
    }

    /**
     * Posts an approved adjustment and closes out its linked task. A recomputed
     * zero variance (count matches current on-hand — the movements fully explain
     * the original delta) has nothing to post: the adjustment stays approved
     * without a ledger entry.
     */
    private void postApprovedAdjustment(CycleCountAdjustment adjustment) {
        if (!Quantities.isZero(adjustment.getQuantityChange())) {
            postAdjustmentToLedger(adjustment);
        } else {
            log.info(
                    "Adjustment {} has zero recomputed variance; nothing to post to the ledger",
                    adjustment.getAdjustmentId());
        }
        markLinkedTaskApproved(adjustment);
    }

    private void markLinkedTaskApproved(CycleCountAdjustment adjustment) {
        if (adjustment.getTaskId() == null) {
            return;
        }
        taskRepository.findById(adjustment.getTaskId()).ifPresent(task -> {
            task.setStatus(TaskStatus.APPROVED);
            taskRepository.save(task);
        });
    }

    private void postAdjustmentToLedger(CycleCountAdjustment adjustment) {
        log.info("Posting adjustment {} to inventory ledger", adjustment.getAdjustmentId());

        try {
            UUID locationId = taskLocationOf(adjustment);
            BigDecimal currentOnHand = currentOnHand(adjustment.getStockItemId(), locationId);
            BigDecimal quantityAfter = currentOnHand.add(adjustment.getQuantityChange());

            InventoryLedgerEventType eventType = Quantities.isPositive(adjustment.getQuantityChange())
                    ? InventoryLedgerEventType.COUNT_VARIANCE_IN
                    : InventoryLedgerEventType.COUNT_VARIANCE_OUT;

            InventoryLedgerEntry ledgerEntry = InventoryLedgerEntry.builder()
                    .stockItemId(adjustment.getStockItemId().toString())
                    .locationId(locationId)
                    .adjustmentId(adjustment.getAdjustmentId())
                    .eventType(eventType)
                    .changeInQuantity(adjustment.getQuantityChange())
                    .quantityAfter(quantityAfter)
                    .unitCost(adjustment.getCostAtTimeOfAdjustment())
                    .transactionUserId(
                            adjustment.getApprovedByUserId() != null
                                    ? adjustment.getApprovedByUserId()
                                    : adjustment.getCreatedByUserId())
                    .notes(String.format("Cycle count adjustment: %s", adjustment.getReasonCode()))
                    .build();

            ledgerEntry = ledgerPostingService.post(ledgerEntry);
            adjustment.setLedgerEntryId(ledgerEntry.getLedgerEntryId());
            adjustment.setStatus(AdjustmentStatus.POSTED);
            adjustment.setPostedAt(Instant.now(clock));
            adjustmentRepository.save(adjustment);

            log.info(
                    "Adjustment {} posted to ledger as entry {}. New on-hand for {}: {}",
                    adjustment.getAdjustmentId(),
                    ledgerEntry.getLedgerEntryId(),
                    adjustment.getStockItemId(),
                    quantityAfter);
        } catch (Exception e) {
            log.error("Failed to post adjustment {} to ledger", adjustment.getAdjustmentId(), e);
            adjustment.setStatus(AdjustmentStatus.FAILED);
            adjustment.setErrorMessage(e.getMessage());
            adjustmentRepository.save(adjustment);
            throw new AdjustmentLedgerPostingException(
                    adjustment.getAdjustmentId(), "Failed to post adjustment to ledger", e);
        }
    }

    /**
     * The storage location an adjustment's variance posts against: the linked task's bin when it
     * holds a location UUID (the form plan-driven task generation writes), {@code null} for
     * task-less adjustments and free-text bins. Carrying this onto the posted
     * {@code COUNT_VARIANCE_*} entry is what makes a bin-scoped expected-quantity snapshot
     * converge: without it, the correction lands on the NULL-location key and the same shrinkage
     * is re-detected by every subsequent plan for that bin.
     */
    private @Nullable UUID taskLocationOf(CycleCountAdjustment adjustment) {
        if (adjustment.getTaskId() == null) {
            return null;
        }
        return taskRepository
                .findById(adjustment.getTaskId())
                .flatMap(CycleCountConflictDetector::locationIdOf)
                .orElse(null);
    }

    /** Current on-hand for the SKU — location-scoped when a location is known, global otherwise. */
    private @NonNull BigDecimal currentOnHand(@NonNull UUID stockItemId, @Nullable UUID locationId) {
        return Quantities.nz(
                locationId != null
                        ? ledgerRepository.calculateOnHandQuantityAtLocation(stockItemId, locationId)
                        : ledgerRepository.calculateOnHandQuantity(stockItemId));
    }

    /**
     * The J1 engine's per-SKU running cost at adjustment time (odoo-parity J3, ADR-0048): the
     * configured {@code standardCost} under STANDARD (falling back to the running
     * average/latest-receipt memo when unset), otherwise the running weighted-average cost. Returns
     * {@code null} when the SKU has no {@code sku_cost_state} row or no derivable cost, so the
     * caller can fall back to the request-supplied cost.
     */
    private @Nullable BigDecimal resolveEngineCost(@NonNull UUID stockItemId) {
        String sku = stockItemId.toString();
        return costStateRepository
                .findByStockItemId(sku)
                .map(state -> methodResolver.resolve(sku) == CostingMethod.STANDARD
                        ? (state.getStandardCost() != null ? state.getStandardCost() : state.getAvgCost())
                        : state.getAvgCost())
                .orElse(null);
    }

    private void publishMovementAdjustedEvent(
            @NonNull CycleCountAdjustment adjustment,
            @NonNull Instant occurredAt,
            @NonNull String actorPersonId,
            @NonNull String actorUsername,
            @Nullable String correlationId) {
        String resolvedCorrelationId = correlationId != null && !correlationId.isBlank()
                ? correlationId
                : UUIDv7Generator.generate().toString();

        InventoryAuditEvent event = new InventoryAuditEvent(
                1,
                UUIDv7Generator.generate().toString(),
                "MovementAdjusted",
                "inventory.v1.movements",
                occurredAt,
                Instant.now(clock),
                "inventory",
                new AuditActorRef(actorPersonId, actorUsername),
                resolvedCorrelationId,
                new AuditAggregateRef(adjustment.getAdjustmentId(), "CycleCountAdjustment"),
                Map.of(
                        "stockItemId", adjustment.getStockItemId(),
                        "quantityChange", adjustment.getQuantityChange(),
                        "reasonCode", adjustment.getReasonCode()));

        eventPublisher.publishEvent(event);
        log.info("Published MovementAdjusted audit event for adjustment {}", adjustment.getAdjustmentId());
    }

    private AdjustmentResponse toResponse(CycleCountAdjustment adjustment) {
        return AdjustmentResponse.builder()
                .adjustmentId(adjustment.getAdjustmentId())
                .stockItemId(adjustment.getStockItemId())
                .taskId(adjustment.getTaskId())
                .reasonCode(adjustment.getReasonCode())
                .quantityChange(adjustment.getQuantityChange())
                .costAtTimeOfAdjustment(adjustment.getCostAtTimeOfAdjustment())
                .quantityOnHandBefore(adjustment.getQuantityOnHandBefore())
                .countedQuantity(adjustment.getCountedQuantity())
                .unitOfMeasure(baseUnitOfMeasureResolver.resolve(adjustment.getStockItemId()))
                .status(adjustment.getStatus())
                .requiredApprovalTier(adjustment.getRequiredApprovalTier())
                .createdByUserId(adjustment.getCreatedByUserId())
                .approvedByUserId(adjustment.getApprovedByUserId())
                .rejectedByUserId(adjustment.getRejectedByUserId())
                .rejectionReason(adjustment.getRejectionReason())
                .createdAt(adjustment.getCreatedAt())
                .updatedAt(adjustment.getUpdatedAt())
                .approvedAt(adjustment.getApprovedAt())
                .rejectedAt(adjustment.getRejectedAt())
                .postedAt(adjustment.getPostedAt())
                .ledgerEntryId(adjustment.getLedgerEntryId())
                .errorMessage(adjustment.getErrorMessage())
                .varianceValue(adjustment.getVarianceValue())
                .variancePercentage(adjustment.getVariancePercentage())
                .build();
    }
}
