package com.positivity.price.internal.dto;

import com.positivity.price.internal.enums.RestrictionDecision;
import java.util.List;
import java.util.UUID;

public record RestrictionEvaluationResult(
        UUID productId, RestrictionDecision decision, List<UUID> ruleIds, List<String> reasonCodes) {}
