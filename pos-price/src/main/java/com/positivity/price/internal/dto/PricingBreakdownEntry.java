package com.positivity.price.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One pricing rule application entry for quote breakdown.
 *
 * Issue: #51
 */
@Schema(description = "Single pricing-rule application entry within a quote breakdown")
public class PricingBreakdownEntry {

    @Schema(description = "Rule name", example = "Tier Discount Rule")
    private String ruleName;

    @Schema(description = "Rule category/type", example = "CUSTOMER_TIER")
    private String ruleType;

    @Schema(description = "Amount adjusted by this rule")
    private MoneyAmount adjustment;

    @Schema(description = "Running value after this rule adjustment")
    private MoneyAmount resultingValue;

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public MoneyAmount getAdjustment() {
        return adjustment;
    }

    public void setAdjustment(MoneyAmount adjustment) {
        this.adjustment = adjustment;
    }

    public MoneyAmount getResultingValue() {
        return resultingValue;
    }

    public void setResultingValue(MoneyAmount resultingValue) {
        this.resultingValue = resultingValue;
    }
}
