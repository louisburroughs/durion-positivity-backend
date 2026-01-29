package com.positivity.order.service;

import com.positivity.order.internal.dto.*;
import com.positivity.order.internal.exception.InvalidPriceOverrideException;
import com.positivity.order.internal.exception.PriceOverrideNotFoundException;
import com.positivity.order.internal.model.ApprovalRecord;
import com.positivity.order.internal.model.OverrideStatus;
import com.positivity.order.internal.model.PriceOverride;
import com.positivity.order.internal.repository.ApprovalRecordRepository;
import com.positivity.order.internal.repository.PriceOverrideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Implementation of PriceOverrideService.
 * 
 * Handles price override business logic including:
 * - Applying overrides with permission checks
 * - Manager approval workflow
 * - Audit trail creation
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PriceOverrideServiceImpl implements PriceOverrideService {
    
    private final PriceOverrideRepository priceOverrideRepository;
    private final ApprovalRecordRepository approvalRecordRepository;
    
    // Business rule: Discount percentage threshold requiring approval
    private static final BigDecimal APPROVAL_THRESHOLD_PERCENTAGE = BigDecimal.valueOf(10.0);
    // Business rule: Discount amount threshold requiring approval
    private static final BigDecimal APPROVAL_THRESHOLD_AMOUNT = BigDecimal.valueOf(100.0);
    
    @Override
    @Transactional
    public ApplyPriceOverrideResponse applyPriceOverride(ApplyPriceOverrideRequest request, String userId) {
        log.info("Applying price override for order {} line {} by user {}", 
                request.getOrderId(), request.getOrderLineId(), userId);
        
        // Validate business rules
        validateOverrideRequest(request);
        
        // Calculate discount details
        BigDecimal discountAmount = request.getOriginalPrice().subtract(request.getOverridePrice());
        BigDecimal discountPercentage = calculateDiscountPercentage(
                request.getOriginalPrice(), request.getOverridePrice());
        
        // Determine if approval is required
        boolean requiresApproval = requiresApproval(discountAmount, discountPercentage);
        OverrideStatus initialStatus = requiresApproval ? 
                OverrideStatus.PENDING_APPROVAL : OverrideStatus.APPROVED;
        
        // Create price override record
        PriceOverride override = PriceOverride.builder()
                .orderId(request.getOrderId())
                .orderLineId(request.getOrderLineId())
                .productId(request.getProductId())
                .originalPrice(request.getOriginalPrice())
                .overridePrice(request.getOverridePrice())
                .reasonCode(request.getReasonCode())
                .justification(request.getJustification())
                .status(initialStatus)
                .requiresApproval(requiresApproval)
                .requestedByUserId(userId)
                .build();
        
        // Auto-approve if no approval required
        if (!requiresApproval) {
            override.setApprovedAt(Instant.now());
            override.setApprovedByUserId(userId); // Self-approved within authority
        }
        
        override = priceOverrideRepository.save(override);
        
        log.info("Price override created with ID {} - Status: {}, Requires Approval: {}", 
                override.getOverrideId(), override.getStatus(), requiresApproval);
        
        // Build response
        String message = requiresApproval ? 
                "Price override created and pending manager approval" :
                "Price override approved and ready to apply";
        
        return ApplyPriceOverrideResponse.builder()
                .overrideId(override.getOverrideId())
                .orderId(override.getOrderId())
                .orderLineId(override.getOrderLineId())
                .productId(override.getProductId())
                .originalPrice(override.getOriginalPrice())
                .overridePrice(override.getOverridePrice())
                .discountAmount(discountAmount)
                .discountPercentage(discountPercentage)
                .reasonCode(override.getReasonCode())
                .justification(override.getJustification())
                .status(override.getStatus())
                .requiresApproval(requiresApproval)
                .requestedByUserId(userId)
                .createdAt(override.getCreatedAt())
                .message(message)
                .build();
    }
    
    @Override
    @Transactional
    public PriceOverride approvePriceOverride(Long overrideId, ApprovePriceOverrideRequest request,
                                              String approverUserId, String approverRole) {
        log.info("Approving price override {} by user {} with role {}", 
                overrideId, approverUserId, approverRole);
        
        PriceOverride override = getOverrideById(overrideId);
        
        // Validate current status
        if (override.getStatus() != OverrideStatus.PENDING_APPROVAL) {
            throw new InvalidPriceOverrideException(
                    "Cannot approve override in status: " + override.getStatus());
        }
        
        // Update override status
        override.setStatus(OverrideStatus.APPROVED);
        override.setApprovedByUserId(approverUserId);
        override.setApprovedAt(Instant.now());
        
        override = priceOverrideRepository.save(override);
        
        // Create approval record for audit trail
        ApprovalRecord approvalRecord = ApprovalRecord.builder()
                .priceOverrideId(overrideId)
                .reviewerUserId(approverUserId)
                .reviewerRole(approverRole)
                .action("APPROVED")
                .comments(request.getComments())
                .build();
        
        approvalRecordRepository.save(approvalRecord);
        
        log.info("Price override {} approved by {} ({})", overrideId, approverUserId, approverRole);
        
        return override;
    }
    
    @Override
    @Transactional
    public PriceOverride rejectPriceOverride(Long overrideId, RejectPriceOverrideRequest request,
                                             String reviewerUserId, String reviewerRole) {
        log.info("Rejecting price override {} by user {} with role {}", 
                overrideId, reviewerUserId, reviewerRole);
        
        PriceOverride override = getOverrideById(overrideId);
        
        // Validate current status
        if (override.getStatus() != OverrideStatus.PENDING_APPROVAL) {
            throw new InvalidPriceOverrideException(
                    "Cannot reject override in status: " + override.getStatus());
        }
        
        // Update override status
        override.setStatus(OverrideStatus.REJECTED);
        override.setRejectedByUserId(reviewerUserId);
        override.setRejectionReason(request.getReason());
        override.setRejectedAt(Instant.now());
        
        override = priceOverrideRepository.save(override);
        
        // Create approval record for audit trail
        ApprovalRecord approvalRecord = ApprovalRecord.builder()
                .priceOverrideId(overrideId)
                .reviewerUserId(reviewerUserId)
                .reviewerRole(reviewerRole)
                .action("REJECTED")
                .comments(request.getComments())
                .build();
        
        approvalRecordRepository.save(approvalRecord);
        
        log.info("Price override {} rejected by {} ({}): {}", 
                overrideId, reviewerUserId, reviewerRole, request.getReason());
        
        return override;
    }
    
    @Override
    @Transactional(readOnly = true)
    public PriceOverride getOverrideById(Long overrideId) {
        return priceOverrideRepository.findById(overrideId)
                .orElseThrow(() -> new PriceOverrideNotFoundException(overrideId));
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<PriceOverride> getOverridesByOrderId(String orderId) {
        return priceOverrideRepository.findByOrderId(orderId);
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<PriceOverride> getPendingApprovals() {
        return priceOverrideRepository.findByStatusAndRequiresApproval(
                OverrideStatus.PENDING_APPROVAL, true);
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<PriceOverride> getOverridesByDateRange(Instant startDate, Instant endDate) {
        return priceOverrideRepository.findByCreatedAtBetween(startDate, endDate);
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<PriceOverride> getOverridesByStatus(OverrideStatus status) {
        return priceOverrideRepository.findByStatus(status);
    }
    
    /**
     * Validates the override request against business rules.
     */
    private void validateOverrideRequest(ApplyPriceOverrideRequest request) {
        if (request.getOverridePrice().compareTo(request.getOriginalPrice()) > 0) {
            throw new InvalidPriceOverrideException(
                    "Override price cannot be greater than original price");
        }
        
        if (request.getOverridePrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidPriceOverrideException(
                    "Override price cannot be negative");
        }
    }
    
    /**
     * Determines if manager approval is required based on business rules.
     */
    private boolean requiresApproval(BigDecimal discountAmount, BigDecimal discountPercentage) {
        return discountAmount.compareTo(APPROVAL_THRESHOLD_AMOUNT) > 0 || 
               discountPercentage.compareTo(APPROVAL_THRESHOLD_PERCENTAGE) > 0;
    }
    
    /**
     * Calculates discount percentage.
     */
    private BigDecimal calculateDiscountPercentage(BigDecimal originalPrice, BigDecimal overridePrice) {
        if (originalPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return originalPrice.subtract(overridePrice)
                .divide(originalPrice, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
