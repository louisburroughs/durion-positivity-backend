package com.positivity.price.service;

import com.positivity.price.dto.RestrictionEvaluationRequest;
import com.positivity.price.dto.RestrictionEvaluationResult;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Evaluates products against active RestrictionRules.
 * Timeout SLA: 800ms. Fail-safe: throw RestrictionServiceUnavailableException on commit paths.
 * Issue #43.
 */
public interface RestrictionEvaluationService {

    @NonNull List<RestrictionEvaluationResult> evaluate(@NonNull RestrictionEvaluationRequest request);
}