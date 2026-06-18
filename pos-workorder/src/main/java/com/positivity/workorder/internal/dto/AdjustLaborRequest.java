package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for adjusting labor hours manually.
 *
 * <p>
 * Implements CAP-005 Story #159 - Record Labor Performed
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to manually adjust labor hours")
public class AdjustLaborRequest {

    @NotNull(message = "Hours worked is required")
    @Positive(message = "Hours worked must be positive")
    @Schema(description = "Adjusted hours worked", example = "2.5", requiredMode = REQUIRED)
    @JsonProperty("hoursWorked")
    private BigDecimal hoursWorked;

    @NotNull(message = "Adjustment reason is required")
    @Schema(
            description = "Reason for the adjustment",
            example = "Manual correction for break time",
            requiredMode = REQUIRED)
    @JsonProperty("adjustmentReason")
    private String adjustmentReason;
}
