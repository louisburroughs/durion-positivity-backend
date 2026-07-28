package com.positivity.customer.internal.enums;

/**
 * Why a follow-up task exists (Story #1153).
 *
 * <p>{@link #DECLINED_SERVICE_FOLLOWUP} and {@link #SERVICE_DUE_REMINDER} are the two the
 * workorder fact feed will raise automatically once FI-3 (#1133) lands; the rest are raised by
 * a CSR. The type is what lets a queue be worked by intent — chasing declined work is a
 * different conversation from a fleet check-in.
 */
public enum FollowUpType {
    DECLINED_SERVICE_FOLLOWUP,
    SERVICE_DUE_REMINDER,
    FLEET_CHECKIN,
    CAMPAIGN_RESPONSE,
    GENERAL
}
