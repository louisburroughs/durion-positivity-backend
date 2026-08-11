package com.positivity.marketing.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One recipient's send record")
public record CampaignSendResponse(
        @Schema(description = "Send record identifier", requiredMode = REQUIRED)
        UUID campaignSendId,

        @Schema(description = "Campaign", requiredMode = REQUIRED)
        UUID campaignId,

        @Schema(description = "Recipient party", requiredMode = REQUIRED)
        UUID recipientPartyId,

        @Schema(description = "Contact addressed, when one was resolved", requiredMode = NOT_REQUIRED)
        UUID contactId,

        @Schema(
                description = "Channel",
                allowableValues = {"EMAIL", "SMS"},
                requiredMode = REQUIRED)
        String channel,

        @Schema(
                description = "Send state",
                allowableValues = {"PENDING", "SENT", "DELIVERED", "BOUNCED", "COMPLAINED", "SUPPRESSED", "FAILED"},
                requiredMode = REQUIRED)
        String status,

        @Schema(description = "Why the send was suppressed or failed", requiredMode = NOT_REQUIRED)
        String failureReason,

        @Schema(description = "Provider's message identifier", requiredMode = NOT_REQUIRED)
        String providerMessageId,

        @Schema(description = "Delivery attempts made", requiredMode = REQUIRED)
        int attempts,

        @Schema(description = "When the recipient was queued", requiredMode = REQUIRED)
        Instant queuedAt,

        @Schema(description = "When the message was dispatched", requiredMode = NOT_REQUIRED)
        Instant sentAt) {}
