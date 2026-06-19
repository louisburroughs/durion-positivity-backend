package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
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

    @Schema(description = "Display name of the travel buffer policy", example = "Standard Metro Buffer", requiredMode = REQUIRED)
    @NotBlank
    private String name;

    @Schema(description = "Type of buffer the policy applies", example = "FIXED_MINUTES", requiredMode = REQUIRED)
    @NotBlank
    private String bufferType;

    @Schema(
            description = "Numeric value of the buffer (interpretation depends on buffer type)",
            example = "30",
            requiredMode = NOT_REQUIRED)
    @PositiveOrZero
    private BigDecimal bufferValue;

    @Schema(description = "Free-text notes about the policy", example = "Applies during peak hours only", requiredMode = NOT_REQUIRED)
    private String notes;
}
