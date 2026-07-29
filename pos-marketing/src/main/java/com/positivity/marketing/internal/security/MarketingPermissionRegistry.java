package com.positivity.marketing.internal.security;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Marketing permission constants (Story #1145).
 *
 * <p>Registration itself is driven by {@code permissions.yaml} per ADR-0025; these constants
 * exist so controllers reference a compiled symbol rather than a loose string that can drift
 * from the catalog.
 *
 * <p>Permission format: {@code marketing:resource:action}.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MarketingPermissionRegistry {

    // Campaigns
    public static final String CAMPAIGN_VIEW = "marketing:campaign:view";
    public static final String CAMPAIGN_CREATE = "marketing:campaign:create";
    public static final String CAMPAIGN_EDIT = "marketing:campaign:edit";
    public static final String CAMPAIGN_SCHEDULE = "marketing:campaign:schedule";
    /** Separate from schedule: dispatching to real recipients is the irreversible step. */
    public static final String CAMPAIGN_SEND = "marketing:campaign:send";

    public static final String CAMPAIGN_MANAGE = "marketing:campaign:manage";

    // Templates
    public static final String TEMPLATE_VIEW = "marketing:template:view";
    public static final String TEMPLATE_MANAGE = "marketing:template:manage";

    // Analytics
    public static final String STATS_VIEW = "marketing:stats:view";
}
