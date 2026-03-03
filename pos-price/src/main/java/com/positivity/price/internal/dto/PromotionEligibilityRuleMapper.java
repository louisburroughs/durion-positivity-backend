package com.positivity.price.internal.dto;

import com.positivity.price.internal.entity.PromotionEligibilityRule;
import com.positivity.price.service.EligibilityDecision;

/** Maps promotion eligibility domain models to API response DTOs. Issue: #96 */
public final class PromotionEligibilityRuleMapper {

    private PromotionEligibilityRuleMapper() {}

    public static EligibilityRuleResponse toResponse(PromotionEligibilityRule rule) {
        EligibilityRuleResponse response = new EligibilityRuleResponse();
        response.setRuleId(rule.getRuleId());
        response.setPromotionId(rule.getPromotionId());
        response.setConditionType(rule.getConditionType());
        response.setOperator(rule.getOperator());
        response.setValue(rule.getValue());
        return response;
    }

    public static EligibilityDecisionResponse toDecisionResponse(EligibilityDecision decision) {
        EligibilityDecisionResponse response = new EligibilityDecisionResponse();
        response.setEligible(decision.isEligible());
        response.setReasonCode(decision.reasonCode());
        return response;
    }
}
