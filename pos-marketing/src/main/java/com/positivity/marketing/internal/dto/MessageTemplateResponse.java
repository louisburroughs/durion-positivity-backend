package com.positivity.marketing.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Schema(description = "A channel-specific message template")
public record MessageTemplateResponse(
        @Schema(description = "Template identifier", requiredMode = REQUIRED)
        UUID templateId,

        @Schema(description = "Template name", requiredMode = REQUIRED)
        String name,

        @Schema(
                description = "Delivery channel",
                allowableValues = {"EMAIL", "SMS"},
                requiredMode = REQUIRED)
        String channel,

        @Schema(
                description = "Audience whose token vocabulary applies",
                allowableValues = {"COMMERCIAL", "INDIVIDUAL"},
                requiredMode = REQUIRED)
        String audienceType,

        @Schema(description = "Subject line; null for SMS", requiredMode = NOT_REQUIRED)
        String subject,

        @Schema(description = "Message body with token placeholders", requiredMode = REQUIRED)
        String body,

        @Schema(description = "Tokens available to this template's audience", requiredMode = REQUIRED)
        Set<String> availableTokens,

        @Schema(description = "Creation timestamp", requiredMode = NOT_REQUIRED)
        Instant createdAt,

        @Schema(description = "Last update timestamp", requiredMode = NOT_REQUIRED)
        Instant updatedAt) {}
