package com.positivity.price.service;

import com.positivity.price.dto.CreateRestrictionRuleRequest;
import com.positivity.price.dto.RestrictionRuleResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Manages the lifecycle of RestrictionRule entities.
 * Issue #43: domain:pricing is sole SoR for RestrictionRule lifecycle.
 */
public interface RestrictionRuleService {

    @NonNull RestrictionRuleResponse createRule(@NonNull CreateRestrictionRuleRequest request);

    @NonNull RestrictionRuleResponse getRuleById(@NonNull UUID ruleId);

    @NonNull List<RestrictionRuleResponse> listRules();

    /** Deactivates the rule by setting effectiveTo to today. */
    @NonNull RestrictionRuleResponse deactivateRule(@NonNull UUID ruleId);
}