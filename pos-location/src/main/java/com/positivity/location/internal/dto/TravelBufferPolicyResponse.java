package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for travel buffer policy endpoints.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload describing a travel buffer policy")
public class TravelBufferPolicyResponse {

    @Schema(
            description = "Unique identifier of the travel buffer policy",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(
            description = "Display name of the travel buffer policy",
            example = "Standard Metro Buffer",
            requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(
            description = "Type of buffer the policy applies: FIXED_MINUTES or DISTANCE_TIER (DECISION-LOCATION-015)."
                    + " DISTANCE_TIER is stored, not yet evaluated: nothing evaluates distance until geocoding"
                    + " exists.",
            example = "FIXED_MINUTES",
            allowableValues = {"FIXED_MINUTES", "DISTANCE_TIER"},
            requiredMode = NOT_REQUIRED)
    private String bufferType;

    @Schema(
            description = "Numeric value of the buffer; for FIXED_MINUTES a non-negative whole number of minutes,"
                    + " for DISTANCE_TIER an unevaluated placeholder",
            example = "30",
            requiredMode = NOT_REQUIRED)
    private BigDecimal bufferValue;

    @Schema(
            description = "Free-text notes about the policy",
            example = "Applies during peak hours only",
            requiredMode = NOT_REQUIRED)
    private String notes;

    @Schema(
            description = "Timestamp when the policy was created (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the policy was last updated (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;
}
