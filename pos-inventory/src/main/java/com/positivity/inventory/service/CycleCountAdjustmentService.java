package com.positivity.inventory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.inventory.dto.cyclecount.*;
import com.positivity.inventory.model.InventoryLedgerEntry;
import com.positivity.inventory.model.InventoryLedgerEventType;
import com.positivity.inventory.model.cyclecount.AdjustmentStatus;
import com.positivity.inventory.model.cyclecount.ApprovalTier;
import com.positivity.inventory.model.cyclecount.CycleCountAdjustment;
import com.positivity.inventory.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.repository.InventoryLedgerEntryRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for managing cycle count adjustments.
 * 
 * <p>Implements the complete lifecycle:
 * <ol>
 *   <li>Create adjustment and evaluate against thresholds</li>
 *   <li>Auto-approve if below thresholds, or queue for manual approval</li>
 *   <li>Handle approval/rejection by authorized users</li>
 *   <li>Post to inventory ledger and update on-hand quantities</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CycleCountAdjustmentService {
    
    private final CycleCountAdjustmentRepository adjustmentRepository;
    private final InventoryLedgerEntryRepository ledgerRepository;
    private final ApprovalThresholdEvaluator thresholdEvaluator;
    
    /**
     * Creates a new cycle count adjustment and evaluates it against approval thresholds.
     * 
     * <p>If the adjustment falls below all thresholds, it is automatically approved and posted.
     * Otherwise, it enters PENDING_APPROVAL status.
     * 
     * @param request the adjustment creation request
     * @return the created adjustment response
     */
    @Transactional
    public AdjustmentResponse createAdjustment(CreateAdjustmentRequest request) {
        log.info("Creating cycle count adjustment for SKU {} by user {}", 
                request.getStockItemId(), request.getCreatedByUserId());
        
        // Calculate quantity change
        int quantityChange = request.getCountedQuantity() - request.getQuantityOnHandBefore();
        
        if (quantityChange == 0) {
            log.info("No variance detected for SKU {}. Count matches system quantity.", 
                    request.getStockItemId());
            throw new IllegalArgumentException("No adjustment needed - counted quantity matches system quantity");
        }
        
        // Create adjustment entity
        CycleCountAdjustment adjustment = CycleCountAdjustment.builder()
                .stockItemId(request.getStockItemId())
                .reasonCode(request.getReasonCode())
                .quantityChange(quantityChange)
                .costAtTimeOfAdjustment(request.getCostAtTimeOfAdjustment())
                .quantityOnHandBefore(request.getQuantityOnHandBefore())
                .countedQuantity(request.getCountedQuantity())
                .createdByUserId(request.getCreatedByUserId())
                .status(AdjustmentStatus.PENDING_APPROVAL) // Temporary, will be updated
                .build();
        
        // Evaluate approval requirements
        Optional<ApprovalTier> requiredTier = thresholdEvaluator.evaluateRequiredApprovalTier(adjustment);
        
        if (requiredTier.isPresent()) {
            // Requires manual approval
            adjustment.setRequiredApprovalTier(requiredTier.get());
            adjustment.setStatus(AdjustmentStatus.PENDING_APPROVAL);
            adjustment = adjustmentRepository.save(adjustment);
            
            log.info("Adjustment {} created with status PENDING_APPROVAL (tier: {})", 
                    adjustment.getAdjustmentId(), requiredTier.get());
            
            // TODO: Emit notification event for approvers
            
        } else {
            // Auto-approve and post
            adjustment.setStatus(AdjustmentStatus.AUTO_APPROVED);
            adjustment.setApprovedByUserId("SYSTEM");
            adjustment.setApprovedAt(Instant.now());
            adjustment = adjustmentRepository.save(adjustment);
            
            log.info("Adjustment {} auto-approved (below thresholds)", adjustment.getAdjustmentId());
            
            // Post to ledger immediately
            postAdjustmentToLedger(adjustment);
            
            // TODO: Emit InventoryAdjustmentAutoApproved event
        }
        
        return toResponse(adjustment);
    }
    
    /**
     * Approves a pending adjustment.
     * 
     * <p>Only adjustments in PENDING_APPROVAL status can be approved.
     * After approval, the adjustment is posted to the inventory ledger.
     * 
     * @param adjustmentId the adjustment ID
     * @param request the approval request
     * @return the updated adjustment response
     */
    @Transactional
    public AdjustmentResponse approveAdjustment(Long adjustmentId, ApproveAdjustmentRequest request) {
        log.info("Approving adjustment {} by user {}", adjustmentId, request.getApproverUserId());
        
        CycleCountAdjustment adjustment = adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("Adjustment not found: " + adjustmentId));
        
        if (adjustment.getStatus() != AdjustmentStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Cannot approve adjustment in status: " + adjustment.getStatus());
        }
        
        // TODO: Verify that approverUserId has INVENTORY_ADJUSTMENT_APPROVE permission
        // TODO: Verify that approver has sufficient tier level for this adjustment
        
        adjustment.setStatus(AdjustmentStatus.APPROVED);
        adjustment.setApprovedByUserId(request.getApproverUserId());
        adjustment.setApprovedAt(Instant.now());
        adjustment = adjustmentRepository.save(adjustment);
        
        log.info("Adjustment {} approved by {}", adjustmentId, request.getApproverUserId());
        
        // Post to ledger
        postAdjustmentToLedger(adjustment);
        
        return toResponse(adjustment);
    }
    
    /**
     * Rejects a pending adjustment.
     * 
     * <p>Only adjustments in PENDING_APPROVAL status can be rejected.
     * Rejection is final - no ledger entry is created and on-hand is not changed.
     * 
     * @param adjustmentId the adjustment ID
     * @param request the rejection request
     * @return the updated adjustment response
     */
    @Transactional
    public AdjustmentResponse rejectAdjustment(Long adjustmentId, RejectAdjustmentRequest request) {
        log.info("Rejecting adjustment {} by user {}: {}", 
                adjustmentId, request.getRejectorUserId(), request.getRejectionReason());
        
        CycleCountAdjustment adjustment = adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("Adjustment not found: " + adjustmentId));
        
        if (adjustment.getStatus() != AdjustmentStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Cannot reject adjustment in status: " + adjustment.getStatus());
        }
        
        // TODO: Verify that rejectorUserId has INVENTORY_ADJUSTMENT_APPROVE permission
        
        adjustment.setStatus(AdjustmentStatus.REJECTED);
        adjustment.setRejectedByUserId(request.getRejectorUserId());
        adjustment.setRejectionReason(request.getRejectionReason());
        adjustment.setRejectedAt(Instant.now());
        adjustment = adjustmentRepository.save(adjustment);
        
        log.info("Adjustment {} rejected by {}", adjustmentId, request.getRejectorUserId());
        
        return toResponse(adjustment);
    }
    
    /**
     * Retrieves a specific adjustment by ID.
     * 
     * @param adjustmentId the adjustment ID
     * @return the adjustment response
     */
    public AdjustmentResponse getAdjustment(Long adjustmentId) {
        CycleCountAdjustment adjustment = adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("Adjustment not found: " + adjustmentId));
        return toResponse(adjustment);
    }
    
    /**
     * Lists all adjustments with a specific status.
     * 
     * @param status the adjustment status
     * @return list of matching adjustments
     */
    public List<AdjustmentResponse> listAdjustmentsByStatus(AdjustmentStatus status) {
        return adjustmentRepository.findByStatus(status).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }
    
    /**
     * Lists all pending approvals.
     * 
     * @return list of adjustments awaiting approval
     */
    public List<AdjustmentResponse> listPendingApprovals() {
        return listAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL);
    }
    
    /**
     * Gets the count of pending approvals.
     * 
     * @return count of adjustments awaiting approval
     */
    public long countPendingApprovals() {
        return adjustmentRepository.countByStatus(AdjustmentStatus.PENDING_APPROVAL);
    }
    
    /**
     * Posts an approved adjustment to the inventory ledger.
     * 
     * <p>Creates an immutable ledger entry and updates the adjustment status to POSTED.
     * 
     * @param adjustment the approved adjustment
     */
    private void postAdjustmentToLedger(CycleCountAdjustment adjustment) {
        log.info("Posting adjustment {} to inventory ledger", adjustment.getAdjustmentId());
        
        try {
            // Calculate new quantity on-hand
            Integer currentOnHand = ledgerRepository.calculateOnHandQuantity(adjustment.getStockItemId());
            Integer quantityAfter = currentOnHand + adjustment.getQuantityChange();
            
            // Determine appropriate ledger event type
            InventoryLedgerEventType eventType = adjustment.getQuantityChange() > 0 
                    ? InventoryLedgerEventType.COUNT_VARIANCE_IN 
                    : InventoryLedgerEventType.COUNT_VARIANCE_OUT;
            
            // Create ledger entry
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
            
            // Update adjustment with ledger reference
            adjustment.setLedgerEntryId(ledgerEntry.getLedgerEntryId());
            adjustment.setStatus(AdjustmentStatus.POSTED);
            adjustment.setPostedAt(Instant.now());
            adjustmentRepository.save(adjustment);
            
            log.info("Adjustment {} posted to ledger as entry {}. New on-hand for {}: {}", 
                    adjustment.getAdjustmentId(), ledgerEntry.getLedgerEntryId(), 
                    adjustment.getStockItemId(), quantityAfter);
            
            // TODO: Emit InventoryAdjustmentPosted event
            
        } catch (Exception e) {
            log.error("Failed to post adjustment {} to ledger", adjustment.getAdjustmentId(), e);
            adjustment.setStatus(AdjustmentStatus.FAILED);
            adjustment.setErrorMessage(e.getMessage());
            adjustmentRepository.save(adjustment);
            throw new RuntimeException("Failed to post adjustment to ledger", e);
        }
    }
    
    /**
     * Converts an adjustment entity to a response DTO.
     * 
     * @param adjustment the adjustment entity
     * @return the response DTO
     */
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
