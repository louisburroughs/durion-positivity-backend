package com.positivity.workorder.service;

import java.util.UUID;

import com.positivity.workorder.internal.dto.PromotionValidationResult;
import com.positivity.workorder.internal.exception.PromotionValidationException;

public interface PromotionValidationService {

    /**
     * Validate all preconditions for promoting an estimate to a workorder.
     * Throws PromotionValidationException if any validation fails.
     * 
     * @param estimateId The estimate to validate for promotion
     * @throws PromotionValidationException if validation fails
     */
    void validatePromotionPreconditions(UUID estimateId);

    /**
     * Validate promotion preconditions and return structured result.
     * Non-throwing variant that returns validation result object.
     * 
     * @param estimateId The estimate to validate for promotion
     * @return Validation result with status and error details
     */
    PromotionValidationResult validatePromotionPreconditionsNonThrowing(UUID estimateId);

}