package com.positivity.price.internal.enums;

/** Defines reason codes for promotion eligibility decisions. Issue: #96 */
public enum EligibilityReasonCode {
    ELIGIBLE,
    ACCOUNT_NOT_IN_LIST,
    ACCOUNT_IN_EXCLUSION_LIST,
    VEHICLE_TAG_NOT_PRESENT,
    VEHICLE_TAG_EXCLUDED,
    FLEET_SIZE_TOO_SMALL,
    AUDIENCE_TYPE_NOT_MATCHED,
    AUDIENCE_TYPE_EXCLUDED,
    CAMPAIGN_CODE_NOT_MATCHED,
    CAMPAIGN_CODE_EXCLUDED,
    MISSING_ACCOUNT_CONTEXT,
    MISSING_AUDIENCE_CONTEXT,
    MISSING_CAMPAIGN_CONTEXT,
    /** Context extension; maps to internal service error. */
    MISSING_VEHICLE_CONTEXT,
    /**
     * Fail-safe code used when an unexpected exception occurs during evaluation.
     */
    EVALUATION_ERROR
}
