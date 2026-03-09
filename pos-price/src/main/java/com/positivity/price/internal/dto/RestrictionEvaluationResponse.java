package com.positivity.price.internal.dto;

import com.positivity.price.dto.RestrictionEvaluationResult;
import java.util.List;

public record RestrictionEvaluationResponse(List<RestrictionEvaluationResult> results) {
}