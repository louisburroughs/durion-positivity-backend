package com.positivity.price.dto;

import com.positivity.price.enums.RestrictionDecision;
import java.util.List;
import java.util.UUID;

public record RestrictionEvaluationResult(
    UUID productId,
    RestrictionDecision decision,
    List<UUID> ruleIds,
    List<String> reasonCodes
) {}