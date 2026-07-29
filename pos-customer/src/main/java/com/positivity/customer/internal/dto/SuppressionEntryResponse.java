package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A hard address-level marketing block")
public record SuppressionEntryResponse(
        @Schema(description = "Suppression identifier", requiredMode = REQUIRED)
        UUID suppressionId,

        @Schema(
                description = "Channel the block applies to",
                example = "EMAIL",
                allowableValues = {"EMAIL", "SMS"},
                requiredMode = REQUIRED)
        String channel,

        @Schema(
                description = "Masked address; the raw value is never stored or returned",
                example = "j***@example.com",
                requiredMode = NOT_REQUIRED)
        String addressHint,

        @Schema(description = "Party the address belonged to when blocked", requiredMode = NOT_REQUIRED)
        UUID partyId,

        @Schema(description = "Why the address is blocked", example = "HARD_BOUNCE", requiredMode = REQUIRED)
        String reason,

        @Schema(description = "Where the block originated", example = "SYSTEM", requiredMode = REQUIRED)
        String source,

        @Schema(description = "Actor that created the block", requiredMode = NOT_REQUIRED)
        String createdBy,

        @Schema(description = "When the block was created", requiredMode = REQUIRED)
        Instant createdAt) {}
