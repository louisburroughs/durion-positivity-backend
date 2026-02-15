package com.positivity.workorder.service;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateStatus;
import com.positivity.workorder.internal.entity.ApprovalStatus;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.exception.PromotionValidationException;
import com.positivity.workorder.internal.exception.PromotionValidationException.PromotionErrorCode;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for validating estimate promotion preconditions.
 * Implements validation rules from CAP:004 Story #25:
 * - Idempotency: Detect duplicate promotion attempts
 * - Approval validity: Check approval status and expiration
 * - Approved scope: Verify approved items exist
 * 
 * Public API for cross-module use; implementation details are internal.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromotionValidationService {

    private final EstimateRepository estimateRepository;
    private final EstimateItemRepository estimateItemRepository;
    private final WorkorderRepository workorderRepository;

    /**
     * Validate all preconditions for promoting an estimate to a workorder.
     * Throws PromotionValidationException if any validation fails.
     * 
     * @param estimateId The estimate to validate for promotion
     * @throws PromotionValidationException if validation fails
     */
    @Transactional(readOnly = true)
    public void validatePromotionPreconditions(@NonNull UUID estimateId) {
        log.debug("Validating promotion preconditions for estimate {}", estimateId);

        // 1. Locate Estimate
        Estimate estimate = estimateRepository.findById(estimateId)
                .orElseThrow(() -> new PromotionValidationException(
                        PromotionErrorCode.ESTIMATE_NOT_FOUND,
                        "Estimate not found: " + estimateId));

        // 2. Idempotency Check - has this estimate already been promoted?
        Optional<Workorder> existingWorkorder = workorderRepository.findFirstByEstimateId(estimateId);
        if (existingWorkorder.isPresent()) {
            UUID workorderId = existingWorkorder.get().getId();
            log.warn("Estimate {} already promoted to workorder {}", estimateId, workorderId);
            throw new PromotionValidationException(
                    PromotionErrorCode.ALREADY_PROMOTED,
                    String.format("Estimate %s has already been promoted to workorder %s",
                            estimateId, workorderId),
                    workorderId);
        }

        // 3. Approval Validity Check - must be in APPROVED status
        if (estimate.getStatus() != EstimateStatus.APPROVED) {
            log.warn("Estimate {} has invalid status for promotion: {}", estimateId, estimate.getStatus());
            throw new PromotionValidationException(
                    PromotionErrorCode.APPROVAL_INVALID,
                    String.format("Estimate status is %s, must be APPROVED for promotion",
                            estimate.getStatus()));
        }

        // 4. Approval Expiration Check
        if (estimate.getExpiresAt() != null && LocalDateTime.now().isAfter(estimate.getExpiresAt())) {
            log.warn("Estimate {} approval has expired at {}", estimateId, estimate.getExpiresAt());
            throw new PromotionValidationException(
                    PromotionErrorCode.APPROVAL_EXPIRED,
                    String.format("Estimate approval expired at %s", estimate.getExpiresAt()));
        }

        // 5. Approved Scope Check - must have at least one approved item
        List<EstimateItem> approvedItems = estimateItemRepository.findByEstimateIdAndApprovalStatus(
                estimateId, ApprovalStatus.APPROVED);

        if (approvedItems.isEmpty()) {
            log.warn("Estimate {} has no approved items for promotion", estimateId);
            throw new PromotionValidationException(
                    PromotionErrorCode.NO_APPROVED_ITEMS,
                    String.format("Estimate %s has no approved items", estimateId));
        }

        log.info("Promotion preconditions validated successfully for estimate {} with {} approved items",
                estimateId, approvedItems.size());
    }

    /**
     * Validate promotion preconditions and return structured result.
     * Non-throwing variant that returns validation result object.
     * 
     * @param estimateId The estimate to validate for promotion
     * @return Validation result with status and error details
     */
    @Transactional(readOnly = true)
    @NonNull
    public PromotionValidationResult validatePromotionPreconditionsNonThrowing(@NonNull UUID estimateId) {
        try {
            validatePromotionPreconditions(estimateId);
            return PromotionValidationResult.success();
        } catch (PromotionValidationException e) {
            if (e.getExistingWorkorderId() != null) {
                return PromotionValidationResult.duplicate(
                        e.getExistingWorkorderId(),
                        e.getMessage());
            }
            return PromotionValidationResult.failure(
                    e.getErrorCode().name(),
                    e.getMessage());
        }
    }
}
