package com.positivity.price.service;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

import com.positivity.price.internal.dto.CreateRestrictionRuleRequest;
import com.positivity.price.internal.dto.RestrictionRuleResponse;

/**
 * Manages the lifecycle of RestrictionRule entities.
 * Issue #43: domain:pricing is sole SoR for RestrictionRule lifecycle.
 */
public interface RestrictionRuleService {

    @NonNull
    RestrictionRuleResponse createRule(@NonNull CreateRestrictionRuleRequest request);

    @NonNull
    RestrictionRuleResponse getRuleById(@NonNull UUID ruleId);

    @NonNull
    List<RestrictionRuleResponse> listRules();

    /** Deactivates the rule by setting effectiveTo to today. */
    @NonNull
    RestrictionRuleResponse deactivateRule(@NonNull UUID ruleId);
}