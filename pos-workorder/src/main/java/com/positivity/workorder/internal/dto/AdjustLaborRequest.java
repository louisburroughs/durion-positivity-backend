package com.positivity.workorder.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for adjusting labor hours manually.
 * 
 * <p>Implements CAP-005 Story #159 - Record Labor Performed
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to manually adjust labor hours")
public class AdjustLaborRequest {
    
    @NotNull(message = "Hours worked is required")
    @Positive(message = "Hours worked must be positive")
    @Schema(description = "Adjusted hours worked", example = "2.5")
    @JsonProperty("hoursWorked")
    private BigDecimal hoursWorked;
    
    @NotNull(message = "Adjustment reason is required")
    @Schema(description = "Reason for the adjustment", example = "Manual correction for break time")
    @JsonProperty("adjustmentReason")
    private String adjustmentReason;
}
