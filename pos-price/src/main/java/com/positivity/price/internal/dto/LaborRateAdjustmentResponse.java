package com.positivity.price.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "A stored labor-matrix step.")
public class LaborRateAdjustmentResponse {

    @Schema(description = "Step identifier")
    private UUID id;

    @Schema(description = "Location; null = platform default matrix")
    private UUID locationId;

    @Schema(description = "Operation category; null = every category")
    private String operationCategory;

    @Schema(description = "Code a quote names to opt this step in", example = "CORROSION")
    private String adjustmentCode;

    @Schema(description = "What the step is for")
    private String description;

    @Schema(description = "PERCENT or FIXED", example = "PERCENT")
    private String adjustmentType;

    @Schema(description = "Configured value", example = "15.0000")
    private BigDecimal adjustmentValue;

    @Schema(description = "Application order within the matrix", example = "10")
    private int sequence;

    @Schema(description = "Start of the effective window, inclusive")
    private Instant effectiveFrom;

    @Schema(description = "End of the effective window, exclusive; null = open-ended")
    private Instant effectiveTo;
}
