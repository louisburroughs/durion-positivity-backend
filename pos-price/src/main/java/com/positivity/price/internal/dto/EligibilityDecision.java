package com.positivity.price.internal.dto;

import com.positivity.price.internal.enums.EligibilityReasonCode;
import org.jspecify.annotations.NonNull;

/**
 * Eligibility evaluation result returned by EligibilityEvaluationService.
 * Issue: #96
 */
public record EligibilityDecision(
        boolean isEligible, @NonNull EligibilityReasonCode reasonCode) {}
