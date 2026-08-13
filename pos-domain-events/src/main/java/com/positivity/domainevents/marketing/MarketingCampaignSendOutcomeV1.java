package com.positivity.domainevents.marketing;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: the shared platform sender reported a terminal outcome for one campaign send
 * (Story #1150).
 *
 * <p>Published by pos-marketing on {@code marketing.events.v1} with one of three event types —
 * {@link #EVENT_TYPE_DELIVERED}, {@link #EVENT_TYPE_BOUNCED}, {@link #EVENT_TYPE_COMPLAINED} —
 * mirroring the send-state transition applied to {@code CampaignSend}. The payload shape is
 * identical across the three so consumers can share one deserializer; {@code outcome} repeats
 * the discriminator for consumers that route on payload alone.
 *
 * <p>The envelope's aggregateId is the recipient party (matching
 * {@link MarketingCampaignSentV1}): per-party ordering is what a timeline or a suppression
 * decision needs.
 *
 * @param campaignSendId the send record — consumers' idempotency key alongside the envelope id
 * @param campaignId campaign that produced the send
 * @param campaignCode stable campaign code, so consumers need no lookup
 * @param recipientPartyId party contacted (also the envelope aggregateId)
 * @param channel EMAIL or SMS
 * @param outcome DELIVERED, BOUNCED, or COMPLAINED
 * @param reason provider's reason for a bounce or complaint, when it gave one
 */
public record MarketingCampaignSendOutcomeV1(
        @NonNull UUID campaignSendId,
        @NonNull UUID campaignId,
        @NonNull String campaignCode,
        @NonNull UUID recipientPartyId,
        @NonNull String channel,
        @NonNull String outcome,
        @Nullable String reason) {

    public static final String EVENT_TYPE_DELIVERED = "marketing.campaign.send.delivered";
    public static final String EVENT_TYPE_BOUNCED = "marketing.campaign.send.bounced";
    public static final String EVENT_TYPE_COMPLAINED = "marketing.campaign.send.complained";
    public static final int SCHEMA_VERSION = 1;

    public MarketingCampaignSendOutcomeV1 {
        if (campaignSendId == null) {
            throw new IllegalArgumentException("campaignSendId must not be null");
        }
        if (campaignId == null) {
            throw new IllegalArgumentException("campaignId must not be null");
        }
        if (recipientPartyId == null) {
            throw new IllegalArgumentException("recipientPartyId must not be null");
        }
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be blank");
        }
        if (outcome == null || outcome.isBlank()) {
            throw new IllegalArgumentException("outcome must not be blank");
        }
    }
}
