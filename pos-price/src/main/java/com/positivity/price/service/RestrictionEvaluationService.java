package com.positivity.price.service;

import java.util.List;
import org.jspecify.annotations.NonNull;

import com.positivity.price.internal.dto.RestrictionEvaluationRequest;
import com.positivity.price.internal.dto.RestrictionEvaluationResult;

/**
 * Evaluates products against active RestrictionRules.
 * Timeout SLA: 800ms. Fail-safe: throw RestrictionServiceUnavailableException
 * on commit paths.
 * Issue #43.
 */
public interface RestrictionEvaluationService {

    @NonNull
    List<RestrictionEvaluationResult> evaluate(@NonNull RestrictionEvaluationRequest request);
}