package com.positivity.price.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.positivity.price.internal.enums.ConditionType;
import com.positivity.price.internal.enums.RuleOperator;
import java.util.UUID;

/** API response payload for a promotion eligibility rule. Issue: #96 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EligibilityRuleResponse {

    private UUID ruleId;
    private UUID promotionId;
    private ConditionType conditionType;
    private RuleOperator operator;
    private String value;

    public UUID getRuleId() {
        return ruleId;
    }

    public void setRuleId(UUID ruleId) {
        this.ruleId = ruleId;
    }

    public UUID getPromotionId() {
        return promotionId;
    }

    public void setPromotionId(UUID promotionId) {
        this.promotionId = promotionId;
    }

    public ConditionType getConditionType() {
        return conditionType;
    }

    public void setConditionType(ConditionType conditionType) {
        this.conditionType = conditionType;
    }

    public RuleOperator getOperator() {
        return operator;
    }

    public void setOperator(RuleOperator operator) {
        this.operator = operator;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
