package com.positivity.price.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.positivity.price.internal.enums.ConditionType;
import com.positivity.price.internal.enums.RuleOperator;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** API response payload for a promotion eligibility rule. Issue: #96 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Response payload representing a stored promotion eligibility rule")
public class EligibilityRuleResponse {

    @Schema(description = "Eligibility rule identifier", example = "f51d2c5b-a1f2-4f4e-a7cf-4e7b1752e6ab")
    private UUID ruleId;

    @Schema(
            description = "Promotion identifier to which this rule belongs",
            example = "f51d2c5b-a1f2-4f4e-a7cf-4e7b1752e6aa")
    private UUID promotionId;

    @Schema(description = "Rule condition type", example = "ACCOUNT_FLEET_SIZE")
    private ConditionType conditionType;

    @Schema(description = "Comparison operator", example = "GREATER_THAN_OR_EQUAL_TO")
    private RuleOperator operator;

    @Schema(description = "Rule threshold or value", example = "1000")
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
