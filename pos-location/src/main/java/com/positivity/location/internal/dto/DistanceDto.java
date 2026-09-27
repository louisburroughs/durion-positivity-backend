package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A distance carried with its explicit unit (DECISION-LOCATION-028): every distance in a request or
 * response names {@code KM} or {@code MI} alongside the number, and a bare number is never accepted.
 * Storage is always canonical kilometres; conversion happens at the API edge via {@link
 * com.positivity.location.internal.service.DistanceUnits}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A distance with its explicit unit; a bare number is refused")
public class DistanceDto {

    @Schema(description = "Numeric distance value, non-negative", example = "25.5", requiredMode = REQUIRED)
    private BigDecimal value;

    @Schema(
            description = "Unit the value is expressed in",
            example = "KM",
            allowableValues = {"KM", "MI"},
            requiredMode = REQUIRED)
    private String unit;
}
