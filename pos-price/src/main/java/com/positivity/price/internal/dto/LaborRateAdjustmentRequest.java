package com.positivity.price.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(
        description = "One step of a shop's labor matrix: a percentage or flat adjustment a quote opts into"
                + " by naming its code. Steps apply in sequence order because percentages compound.")
public class LaborRateAdjustmentRequest {

    @Schema(description = "Location this step belongs to; omit for the platform default matrix")
    private UUID locationId;

    @Size(max = 32)
    @Schema(
            description = "Operation category this step applies to; omit to apply to every category",
            example = "REPAIR",
            allowableValues = {"REPAIR", "DIAGNOSTIC", "MAINTENANCE", "TIRE_SERVICE"})
    private String operationCategory;

    @NotNull
    @Size(max = 64)
    @Schema(description = "Code a quote names to opt this step in", example = "CORROSION")
    private String adjustmentCode;

    @Size(max = 255)
    @Schema(description = "What the step is for", example = "Seized or corroded fasteners")
    private String description;

    @NotNull
    @Schema(
            description = "PERCENT compounds on the running rate; FIXED adds to it",
            example = "PERCENT",
            allowableValues = {"PERCENT", "FIXED"})
    private String adjustmentType;

    @NotNull
    @Schema(description = "Configured value; negative discounts", example = "15.0")
    private BigDecimal adjustmentValue;

    @NotNull
    @Schema(description = "Application order within the matrix", example = "10")
    private Integer sequence;

    @NotNull
    @Schema(description = "Start of the effective window, inclusive")
    private Instant effectiveFrom;

    @Schema(description = "End of the effective window, exclusive; omit for open-ended")
    private Instant effectiveTo;
}
