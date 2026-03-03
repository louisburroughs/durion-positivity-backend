package com.positivity.inventory.internal.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.inventory.internal.dto.cyclecount.AdjustmentResponse;
import com.positivity.inventory.internal.dto.cyclecount.ApproveAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.CreateAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.RejectAdjustmentRequest;
import com.positivity.inventory.internal.entity.CycleCountAdjustment;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.event.AuditActorRef;
import com.positivity.inventory.internal.event.AuditAggregateRef;
import com.positivity.inventory.internal.event.InventoryAuditEvent;
import com.positivity.inventory.internal.exception.AdjustmentLedgerPostingException;
import com.positivity.inventory.internal.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.service.ApprovalThresholdEvaluator;
import com.positivity.inventory.service.CycleCountAdjustmentService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
        private final ApprovalThresholdEvaluator thresholdEvaluator;
        private final ApplicationEventPublisher eventPublisher;
        private final Clock clock;

        @Override
        @Transactional
        public AdjustmentResponse createAdjustment(CreateAdjustmentRequest request) {
                log.info("Creating cycle count adjustment for SKU {} by user {}",
                                request.getStockItemId(), request.getCreatedByUserId());

                int quantityChange = request.getCountedQuantity() - request.getQuantityOnHandBefore();
                if (quantityChange == 0) {
                        log.info("No variance detected for SKU {}. Count matches system quantity.",
                                        request.getStockItemId());
                        throw new IllegalArgumentException(
                                        "No adjustment needed - counted quantity matches system quantity");
                }

                CycleCountAdjustment adjustment = CycleCountAdjustment.builder()
                                .stockItemId(request.getStockItemId())
                                .reasonCode(request.getReasonCode())
                                .quantityChange(quantityChange)
                                .costAtTimeOfAdjustment(request.getCostAtTimeOfAdjustment())
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

                        log.info("Adjustment {} created with status PENDING_APPROVAL (tier: {})",
                                        adjustment.getAdjustmentId(), requiredTier.get());
                } else {
                        adjustment.setStatus(AdjustmentStatus.AUTO_APPROVED);
                        adjustment.setApprovedByUserId("SYSTEM");
                        adjustment.setApprovedAt(Instant.now(clock));
                        adjustment = adjustmentRepository.save(adjustment);

                        log.info("Adjustment {} auto-approved (below thresholds)", adjustment.getAdjustmentId());
                        postAdjustmentToLedger(adjustment);
                }

                return toResponse(adjustment);
        }

        @Override
        @Transactional
        public AdjustmentResponse approveAdjustment(
                        @NonNull UUID adjustmentId,
                        @NonNull ApproveAdjustmentRequest request,
                        @Nullable String correlationId) {
                String actorUserId = SecurityContextHelper.getCurrentUsername()
                                .orElseThrow(() -> new IllegalStateException("No current user"));
                String actorUsername = SecurityContextHelper.getCurrentUsernameOrDefault("system");
                log.info("Approving adjustment {} by authenticated actor {}", adjustmentId, actorUsername);

                CycleCountAdjustment adjustment = adjustmentRepository.findById(adjustmentId)
                                .orElseThrow(() -> new IllegalArgumentException(ADJUSTMENT_NOT_FOUND + adjustmentId));

                if (adjustment.getStatus() != AdjustmentStatus.PENDING_APPROVAL) {
                        throw new IllegalStateException(
                                        "Cannot approve adjustment in status: " + adjustment.getStatus());
                }

                Instant occurredAt = Instant.now(clock);
                adjustment.setStatus(AdjustmentStatus.APPROVED);
                adjustment.setApprovedByUserId(actorUserId);
                adjustment.setApprovedAt(occurredAt);
                adjustment = adjustmentRepository.save(adjustment);

                log.info("Adjustment {} approved by {} ({})", adjustmentId, actorUsername, actorUserId);
                postAdjustmentToLedger(adjustment);
                publishMovementAdjustedEvent(adjustment, occurredAt, actorUserId, actorUsername, correlationId);

                return toResponse(adjustment);
        }

        @Override
        @Transactional
        public AdjustmentResponse rejectAdjustment(UUID adjustmentId, RejectAdjustmentRequest request) {
                log.info("Rejecting adjustment {} by user {}: {}",
                                adjustmentId, request.getRejectorUserId(), request.getRejectionReason());

                CycleCountAdjustment adjustment = adjustmentRepository.findById(adjustmentId)
                                .orElseThrow(() -> new IllegalArgumentException(ADJUSTMENT_NOT_FOUND + adjustmentId));

                if (adjustment.getStatus() != AdjustmentStatus.PENDING_APPROVAL) {
                        throw new IllegalStateException(
                                        "Cannot reject adjustment in status: " + adjustment.getStatus());
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
                CycleCountAdjustment adjustment = adjustmentRepository.findById(adjustmentId)
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

        private void postAdjustmentToLedger(CycleCountAdjustment adjustment) {
                log.info("Posting adjustment {} to inventory ledger", adjustment.getAdjustmentId());

                try {
                        Integer currentOnHand = ledgerRepository.calculateOnHandQuantity(adjustment.getStockItemId());
                        Integer quantityAfter = currentOnHand + adjustment.getQuantityChange();

                        InventoryLedgerEventType eventType = adjustment.getQuantityChange() > 0
                                        ? InventoryLedgerEventType.COUNT_VARIANCE_IN
                                        : InventoryLedgerEventType.COUNT_VARIANCE_OUT;

                        InventoryLedgerEntry ledgerEntry = InventoryLedgerEntry.builder()
                                        .stockItemId(adjustment.getStockItemId())
                                        .adjustmentId(adjustment.getAdjustmentId())
                                        .eventType(eventType)
                                        .changeInQuantity(adjustment.getQuantityChange())
                                        .quantityAfter(quantityAfter)
                                        .unitCost(adjustment.getCostAtTimeOfAdjustment())
                                        .transactionUserId(adjustment.getApprovedByUserId() != null
                                                        ? adjustment.getApprovedByUserId()
                                                        : adjustment.getCreatedByUserId())
                                        .notes(String.format("Cycle count adjustment: %s", adjustment.getReasonCode()))
                                        .build();

                        ledgerEntry = ledgerRepository.save(ledgerEntry);
                        adjustment.setLedgerEntryId(ledgerEntry.getLedgerEntryId());
                        adjustment.setStatus(AdjustmentStatus.POSTED);
                        adjustment.setPostedAt(Instant.now(clock));
                        adjustmentRepository.save(adjustment);

                        log.info("Adjustment {} posted to ledger as entry {}. New on-hand for {}: {}",
                                        adjustment.getAdjustmentId(), ledgerEntry.getLedgerEntryId(),
                                        adjustment.getStockItemId(), quantityAfter);
                } catch (Exception e) {
                        log.error("Failed to post adjustment {} to ledger", adjustment.getAdjustmentId(), e);
                        adjustment.setStatus(AdjustmentStatus.FAILED);
                        adjustment.setErrorMessage(e.getMessage());
                        adjustmentRepository.save(adjustment);
                        throw new AdjustmentLedgerPostingException(
                                        adjustment.getAdjustmentId(),
                                        "Failed to post adjustment to ledger",
                                        e);
                }
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
                                .reasonCode(adjustment.getReasonCode())
                                .quantityChange(adjustment.getQuantityChange())
                                .costAtTimeOfAdjustment(adjustment.getCostAtTimeOfAdjustment())
                                .quantityOnHandBefore(adjustment.getQuantityOnHandBefore())
                                .countedQuantity(adjustment.getCountedQuantity())
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
