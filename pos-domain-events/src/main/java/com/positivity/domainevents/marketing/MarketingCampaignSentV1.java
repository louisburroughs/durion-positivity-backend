package com.positivity.domainevents.marketing;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a campaign message was dispatched to one recipient (Story #1149).
 *
 * <p>Published by pos-marketing on {@code marketing.events.v1} with
 * {@code eventType = "marketing.campaign.sent"}. pos-customer consumes it to write a
 * {@code CAMPAIGN_SEND} interaction onto the recipient's timeline (Story #1151), which is what
 * lets a CSR see "we emailed them last Tuesday" without querying the marketing module.
 *
 * <p>The envelope's aggregateId is the recipient party, not the campaign: consumers key their
 * work per party, and per-party ordering is what matters for a timeline.
 *
 * @param campaignSendId the send record — consumers' idempotency key alongside the envelope id
 * @param campaignId campaign that produced the send
 * @param campaignCode stable campaign code, so consumers need no lookup
 * @param recipientPartyId party contacted (also the envelope aggregateId)
 * @param contactId specific contact addressed, when one was resolved
 * @param channel EMAIL or SMS
 * @param subject rendered subject line, for display on the recipient's timeline
 */
public record MarketingCampaignSentV1(
        @NonNull UUID campaignSendId,
        @NonNull UUID campaignId,
        @NonNull String campaignCode,
        @NonNull UUID recipientPartyId,
        @Nullable UUID contactId,
        @NonNull String channel,
        @Nullable String subject) {

    public static final String EVENT_TYPE = "marketing.campaign.sent";
    public static final int SCHEMA_VERSION = 1;

    public MarketingCampaignSentV1 {
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
    }
}
