package com.positivity.inventory.internal.dto.cyclecount;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.MeasurementMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to submit a recount for a cycle count task.
 */
@Schema(description = "Request to submit a recount for a cycle count task, including the caller's permission context")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubmitRecountRequest {

    @Schema(
            description = "Identifier of the cycle count task being recounted",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull(message = "Task ID is required")
    private UUID taskId;

    @Schema(
            description = "Identifier of the auditor submitting the recount",
            example = "auditor-10042",
            requiredMode = REQUIRED)
    @NotNull(message = "Auditor ID is required")
    private String auditorId;

    @Schema(
            description = "Quantity physically measured on the recount, in unitOfMeasure (or the product's base"
                    + " UoM when unitOfMeasure is omitted). Converted to base UoM before variance is computed.",
            example = "13",
            requiredMode = REQUIRED)
    @NotNull(message = "Actual quantity is required")
    @DecimalMin(value = "0", message = "Quantity must be zero or positive")
    private BigDecimal actualQuantity;

    @Schema(
            description = "Unit the quantity was physically measured in. Omit for the product's base UoM.",
            example = "GAL",
            requiredMode = NOT_REQUIRED)
    private String unitOfMeasure;

    @Schema(
            description = "How the quantity was obtained. Defaults to MANUAL_COUNT.",
            example = "MANUAL_COUNT",
            requiredMode = NOT_REQUIRED)
    private MeasurementMethod measurementMethod;

    @Schema(
            description = "Reason for the variance, if already known at submission time. Optional.",
            example = "Evaporation since last count",
            requiredMode = NOT_REQUIRED)
    private String varianceReason;

    /**
     * Permission context: TRIGGER_RECOUNT_SELF or TRIGGER_RECOUNT_ANY
     */
    @Schema(
            description = "Permission context authorizing the recount; one of TRIGGER_RECOUNT_SELF or"
                    + " TRIGGER_RECOUNT_ANY",
            example = "TRIGGER_RECOUNT_SELF",
            requiredMode = REQUIRED)
    @NotNull(message = "Permission is required")
    private String permission;
}
