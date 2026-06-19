package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.price.internal.enums.EligibilityReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;

/** API response payload for promotion eligibility evaluation. Issue: #96 */
@Schema(description = "Eligibility evaluation result for a promotion and context")
public class EligibilityDecisionResponse {

    @Schema(
            description = "Whether the promotion is eligible for the provided context",
            example = "true",
            requiredMode = REQUIRED)
    @JsonProperty("isEligible")
    private boolean isEligible;

    @Schema(
            description = "Detailed ineligibility reason when eligibility is false",
            example = "MISSING_ACCOUNT_CONTEXT",
            requiredMode = NOT_REQUIRED)
    private EligibilityReasonCode reasonCode;

    public boolean isEligible() {
        return isEligible;
    }

    public void setEligible(boolean eligible) {
        this.isEligible = eligible;
    }

    public EligibilityReasonCode getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(EligibilityReasonCode reasonCode) {
        this.reasonCode = reasonCode;
    }
}
