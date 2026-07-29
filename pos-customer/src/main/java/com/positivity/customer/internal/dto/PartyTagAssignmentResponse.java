package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A tag attached to a party")
public record PartyTagAssignmentResponse(
        @Schema(description = "Assignment identifier", requiredMode = REQUIRED)
        UUID assignmentId,

        @Schema(description = "Party the tag is attached to", requiredMode = REQUIRED)
        UUID partyId,

        @Schema(description = "Tag identifier", requiredMode = REQUIRED)
        UUID tagId,

        @Schema(description = "Tag name at read time", example = "Fleet Prospect", requiredMode = REQUIRED)
        String name,

        @Schema(description = "Optional display grouping", requiredMode = NOT_REQUIRED)
        String category,

        @Schema(
                description = "How the assignment was made",
                example = "MANUAL",
                allowableValues = {"MANUAL", "CAMPAIGN", "IMPORT", "RULE"},
                requiredMode = REQUIRED)
        String source,

        @Schema(description = "Actor that made the assignment", requiredMode = NOT_REQUIRED)
        String assignedBy,

        @Schema(description = "When the tag was attached", requiredMode = REQUIRED)
        Instant assignedAt) {}
