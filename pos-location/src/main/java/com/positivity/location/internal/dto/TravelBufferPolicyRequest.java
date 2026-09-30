package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for travel buffer policy endpoints.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request payload for creating or updating a travel buffer policy")
public class TravelBufferPolicyRequest {

    @Schema(
            description = "Display name of the travel buffer policy; unique per tenant, at most 255 characters",
            example = "Standard Metro Buffer",
            maxLength = 255,
            requiredMode = REQUIRED)
    @NotBlank
    @Size(min = 1, max = 255)
    private String name;

    @Schema(
            description = "Type of buffer the policy applies; one of FIXED_MINUTES or DISTANCE_TIER"
                    + " (DECISION-LOCATION-015). DISTANCE_TIER is stored, not yet evaluated: nothing evaluates"
                    + " distance until geocoding exists.",
            example = "FIXED_MINUTES",
            allowableValues = {"FIXED_MINUTES", "DISTANCE_TIER"},
            requiredMode = REQUIRED)
    @NotBlank
    private String bufferType;

    @Schema(
            description = "Numeric value of the buffer; for FIXED_MINUTES a non-negative whole number of minutes,"
                    + " for DISTANCE_TIER an unevaluated placeholder",
            example = "30",
            requiredMode = NOT_REQUIRED)
    @PositiveOrZero
    private BigDecimal bufferValue;

    @Schema(
            description = "Free-text notes about the policy; at most 255 characters",
            example = "Applies during peak hours only",
            maxLength = 255,
            requiredMode = NOT_REQUIRED)
    @Size(max = 255)
    private String notes;
}
