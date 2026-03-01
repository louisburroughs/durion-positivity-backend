package com.positivity.inventory.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lead-time query view returned by inventory for product detail aggregation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadTimeView {

    @Schema(description = "Product identifier", example = "650e8400-e29b-41d4-a716-446655440000")
    private UUID productId;

    @Schema(description = "Location identifier", example = "650e8400-e29b-41d4-a716-446655440001")
    private UUID locationId;

    @Schema(description = "Source of lead-time data", example = "INVENTORY")
    private String source;

    @Schema(description = "Minimum lead-time in days", example = "2")
    private Integer minDays;

    @Schema(description = "Maximum lead-time in days", example = "5")
    private Integer maxDays;

    @Schema(description = "Human-readable lead-time text", example = "2-5 business days")
    private String displayText;

    @Schema(description = "Confidence level for this estimate", example = "HIGH")
    private String confidence;

    @Schema(description = "Timestamp when lead-time data was last refreshed")
    private Instant asOf;
}
