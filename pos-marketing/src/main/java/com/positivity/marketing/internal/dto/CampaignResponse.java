package com.positivity.marketing.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Schema(description = "A marketing campaign")
public record CampaignResponse(
        @Schema(description = "Campaign identifier", requiredMode = REQUIRED)
        UUID campaignId,

        @Schema(description = "Stable campaign code", example = "SPRING-FLEET-2026", requiredMode = REQUIRED)
        String code,

        @Schema(description = "Campaign name", requiredMode = REQUIRED)
        String name,

        @Schema(description = "What the campaign is for", requiredMode = NOT_REQUIRED)
        String description,

        @Schema(
                description = "Targeted party kind",
                allowableValues = {"COMMERCIAL", "INDIVIDUAL"},
                requiredMode = REQUIRED)
        String audienceType,

        @Schema(description = "Program grouping the commercial and individual arms", requiredMode = NOT_REQUIRED)
        UUID campaignProgramId,

        @Schema(
                description = "Lifecycle status",
                allowableValues = {"DRAFT", "SCHEDULED", "SENDING", "SENT", "PAUSED", "CANCELLED", "CLOSED"},
                requiredMode = REQUIRED)
        String status,

        @Schema(description = "Delivery channels", requiredMode = REQUIRED)
        Set<String> channels,

        @Schema(description = "Bound pos-customer segment", requiredMode = NOT_REQUIRED)
        UUID segmentId,

        @Schema(description = "Promoted pos-price offer", requiredMode = NOT_REQUIRED)
        UUID promotionOfferId,

        @Schema(description = "pos-catalog focus reference", requiredMode = NOT_REQUIRED)
        String catalogFocusRef,

        @Schema(description = "Start of the active window", requiredMode = NOT_REQUIRED)
        Instant windowStart,

        @Schema(description = "End of the active window", requiredMode = NOT_REQUIRED)
        Instant windowEnd,

        @Schema(
                description = "Dispatch timing",
                allowableValues = {"IMMEDIATE", "SCHEDULED"},
                requiredMode = REQUIRED)
        String scheduleType,

        @Schema(description = "When dispatch begins", requiredMode = NOT_REQUIRED)
        Instant scheduledAt,

        @Schema(description = "Email template", requiredMode = NOT_REQUIRED)
        UUID emailTemplateId,

        @Schema(description = "SMS template", requiredMode = NOT_REQUIRED)
        UUID smsTemplateId,

        @Schema(description = "Creation timestamp", requiredMode = NOT_REQUIRED)
        Instant createdAt,

        @Schema(description = "Last update timestamp", requiredMode = NOT_REQUIRED)
        Instant updatedAt) {}
