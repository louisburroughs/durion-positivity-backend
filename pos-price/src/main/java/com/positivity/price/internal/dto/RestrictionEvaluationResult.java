package com.positivity.price.internal.dto;

import java.util.List;
import java.util.UUID;

import com.positivity.price.internal.enums.RestrictionDecision;

public record RestrictionEvaluationResult(
        UUID productId,
        RestrictionDecision decision,
        List<UUID> ruleIds,
        List<String> reasonCodes) {
}