package com.positivity.price.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.price.internal.enums.EligibilityReasonCode;

/** API response payload for promotion eligibility evaluation. Issue: #96 */
public class EligibilityDecisionResponse {

    @JsonProperty("isEligible")
    private boolean isEligible;
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
