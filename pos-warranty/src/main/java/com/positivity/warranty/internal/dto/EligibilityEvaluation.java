package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.EligibilityResult;

/**
 * Result of {@code EligibilityService.evaluate(claimId)} (PRD §6): the overall result, the
 * per-term pass/fail reason maps, and the computed {@code suggestedOutcome} (settlement type
 * suggestion + per-line proration amounts). The same values are persisted onto the claim.
 */
public record EligibilityEvaluation(
        EligibilityResult result,
        java.util.List<java.util.Map<String, Object>> reasons,
        java.util.Map<String, Object> suggestedOutcome) {}
