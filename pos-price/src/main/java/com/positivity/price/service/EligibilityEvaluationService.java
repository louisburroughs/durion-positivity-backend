package com.positivity.price.service;

import com.positivity.price.internal.dto.AddEligibilityRuleRequest;
import com.positivity.price.internal.entity.PromotionEligibilityRule;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Manages eligibility rules and evaluates promotion eligibility. Issue: #96 */
public interface EligibilityEvaluationService {

    /** Add an eligibility rule to a promotion. Issue: #96 */
    @NonNull
    PromotionEligibilityRule addRule(@NonNull UUID promotionId, @NonNull AddEligibilityRuleRequest request);

    /** Get all eligibility rules for a promotion. Issue: #96 */
    @NonNull
    List<PromotionEligibilityRule> getRules(@NonNull UUID promotionId);

    /** Delete an eligibility rule by ID. Issue: #96 */
    void deleteRule(@NonNull UUID ruleId);

    /** Evaluate promotion eligibility for a given account and vehicle context. Issue: #96 */
    @NonNull
    EligibilityDecision evaluateEligibility(
            @NonNull UUID promotionId, @Nullable UUID accountId, @Nullable UUID vehicleId);
}
