package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * The outcome of resolving a segment.
 *
 * <p>Returns counts and a masked sample rather than the full recipient list: a resolve is a
 * planning call, and handing back every matching party's details would make audience preview
 * a bulk PII export.
 */
@Schema(description = "Result of resolving a segment to its matching parties")
public record SegmentResolutionResponse(
        @Schema(description = "Segment resolved", requiredMode = REQUIRED)
        UUID segmentId,

        @Schema(
                description = "Targeted party kind",
                allowableValues = {"COMMERCIAL", "INDIVIDUAL"},
                requiredMode = REQUIRED)
        String audienceType,

        @Schema(description = "Parties matching the definition", example = "412", requiredMode = REQUIRED)
        long totalMatched,

        @Schema(
                description =
                        "Of those, how many may actually be sent to on the requested channel after consent, account gate, and suppression; null when no channel was requested",
                example = "287",
                requiredMode = NOT_REQUIRED)
        Long eligibleCount,

        @Schema(
                description = "Channel the eligibility count was computed for",
                allowableValues = {"EMAIL", "SMS"},
                requiredMode = NOT_REQUIRED)
        String channel,

        @Schema(
                description = "Whether the candidate scan hit its ceiling and the result is partial",
                example = "false",
                requiredMode = REQUIRED)
        boolean truncated,

        @Schema(description = "A masked sample of matching parties", requiredMode = REQUIRED)
        List<SegmentSampleEntry> sample) {

    @Schema(description = "One masked entry in a segment resolution sample")
    public record SegmentSampleEntry(
            @Schema(description = "Party identifier", requiredMode = REQUIRED)
            UUID partyId,

            @Schema(description = "Masked display label", example = "Acme T***", requiredMode = NOT_REQUIRED)
            String maskedLabel) {}
}
