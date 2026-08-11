package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One recorded touch on a party's interaction timeline")
public record CustomerInteractionResponse(
        @Schema(description = "Interaction identifier", requiredMode = REQUIRED)
        UUID interactionId,

        @Schema(description = "Party the touch belongs to", requiredMode = REQUIRED)
        UUID partyId,

        @Schema(description = "Contact reached, when the touch targeted one", requiredMode = NOT_REQUIRED)
        UUID contactId,

        @Schema(
                description = "Kind of touch",
                example = "CAMPAIGN_SEND",
                allowableValues = {"CAMPAIGN_SEND", "EMAIL", "SMS", "CALL", "FOLLOW_UP", "NOTE", "WORKORDER_NOTE"},
                requiredMode = REQUIRED)
        String type,

        @Schema(
                description = "Channel used",
                example = "EMAIL",
                allowableValues = {"EMAIL", "SMS"},
                requiredMode = NOT_REQUIRED)
        String channel,

        @Schema(
                description = "Who initiated the touch",
                example = "OUTBOUND",
                allowableValues = {"OUTBOUND", "INBOUND"},
                requiredMode = REQUIRED)
        String direction,

        @Schema(description = "Campaign the touch belongs to", requiredMode = NOT_REQUIRED)
        UUID campaignId,

        @Schema(description = "Campaign code", example = "SPRING-FLEET-2026", requiredMode = NOT_REQUIRED)
        String campaignCode,

        @Schema(description = "Short subject line", requiredMode = NOT_REQUIRED)
        String subject,

        @Schema(description = "Human summary of the touch", requiredMode = NOT_REQUIRED)
        String summary,

        @Schema(description = "Body text, redacted before it leaves the service", requiredMode = NOT_REQUIRED)
        String body,

        @Schema(description = "Actor that recorded the touch", requiredMode = NOT_REQUIRED)
        String actor,

        @Schema(description = "Workorder the touch originated from", requiredMode = NOT_REQUIRED)
        String sourceWorkorderId,

        @Schema(description = "When the touch occurred", requiredMode = REQUIRED)
        Instant occurredAt) {}
