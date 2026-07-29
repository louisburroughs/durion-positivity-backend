package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A CRM classification tag that can be attached to parties")
public record PartyTagResponse(
        @Schema(
                description = "Tag identifier",
                example = "01960003-0000-7000-8000-000000000010",
                requiredMode = REQUIRED)
        UUID tagId,

        @Schema(description = "Unique tag name", example = "Fleet Prospect", requiredMode = REQUIRED)
        String name,

        @Schema(description = "Optional display grouping", example = "Lifecycle", requiredMode = NOT_REQUIRED)
        String category,

        @Schema(description = "Presentation colour hint", example = "#1f7a4d", requiredMode = NOT_REQUIRED)
        String color,

        @Schema(description = "Whether the tag can still be assigned", example = "true", requiredMode = REQUIRED)
        boolean active,

        @Schema(description = "Number of parties currently carrying the tag", example = "42", requiredMode = REQUIRED)
        long assignmentCount,

        @Schema(description = "Creation timestamp", requiredMode = NOT_REQUIRED)
        Instant createdAt) {}
