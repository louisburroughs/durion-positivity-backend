package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.customer.internal.domain.SegmentPredicate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A saved audience segment")
public record SegmentResponse(
        @Schema(description = "Segment identifier", requiredMode = REQUIRED)
        UUID segmentId,

        @Schema(description = "Segment name", requiredMode = REQUIRED)
        String name,

        @Schema(description = "What the segment is for", requiredMode = NOT_REQUIRED)
        String description,

        @Schema(
                description = "Targeted party kind",
                example = "COMMERCIAL",
                allowableValues = {"COMMERCIAL", "INDIVIDUAL"},
                requiredMode = REQUIRED)
        String audienceType,

        @Schema(
                description = "How membership is determined",
                example = "DYNAMIC",
                allowableValues = {"STATIC", "DYNAMIC"},
                requiredMode = REQUIRED)
        String type,

        @Schema(description = "Predicate tree for DYNAMIC segments", requiredMode = NOT_REQUIRED)
        SegmentPredicate predicate,

        @Schema(description = "Whether the segment can be bound to a campaign", requiredMode = REQUIRED)
        boolean active,

        @Schema(description = "Pinned member count for STATIC segments; null for DYNAMIC", requiredMode = NOT_REQUIRED)
        Long memberCount,

        @Schema(description = "Creation timestamp", requiredMode = NOT_REQUIRED)
        Instant createdAt,

        @Schema(description = "Last update timestamp", requiredMode = NOT_REQUIRED)
        Instant updatedAt) {}
