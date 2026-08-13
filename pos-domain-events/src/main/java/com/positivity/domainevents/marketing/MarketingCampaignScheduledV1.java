package com.positivity.domainevents.marketing;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a campaign was scheduled for delivery (Story #1146).
 *
 * <p>Published by pos-marketing on {@code marketing.events.v1} with
 * {@code eventType = "marketing.campaign.scheduled"}.
 *
 * @param campaignId scheduled campaign (also the envelope aggregateId)
 * @param code stable campaign code — the attribution key stamped onto redemptions
 * @param audienceType COMMERCIAL or INDIVIDUAL
 * @param segmentId pos-customer segment the campaign is bound to
 * @param scheduledAt when dispatch should begin; null for immediate sends
 */
public record MarketingCampaignScheduledV1(
        @NonNull UUID campaignId,
        @NonNull String code,
        @NonNull String audienceType,
        @Nullable UUID segmentId,
        @Nullable Instant scheduledAt) {

    public static final String EVENT_TYPE = "marketing.campaign.scheduled";
    public static final int SCHEMA_VERSION = 1;

    public MarketingCampaignScheduledV1 {
        if (campaignId == null) {
            throw new IllegalArgumentException("campaignId must not be null");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
    }
}
