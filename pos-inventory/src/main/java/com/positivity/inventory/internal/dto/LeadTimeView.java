package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lead-time query view returned by inventory for product detail aggregation.
 */
@Schema(description = "Lead-time view returned by inventory for product detail aggregation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadTimeView {

    @Schema(
            description = "Product identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID productId;

    @Schema(
            description = "Location identifier",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(description = "Source of lead-time data", example = "INVENTORY", requiredMode = NOT_REQUIRED)
    private String source;

    @Schema(description = "Minimum lead-time in days", example = "2", requiredMode = NOT_REQUIRED)
    private Integer minDays;

    @Schema(description = "Maximum lead-time in days", example = "5", requiredMode = NOT_REQUIRED)
    private Integer maxDays;

    @Schema(description = "Human-readable lead-time text", example = "2-5 business days", requiredMode = NOT_REQUIRED)
    private String displayText;

    @Schema(description = "Confidence level for this estimate", example = "HIGH", requiredMode = NOT_REQUIRED)
    private String confidence;

    @Schema(
            description = "Timestamp when lead-time data was last refreshed",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant asOf;
}
