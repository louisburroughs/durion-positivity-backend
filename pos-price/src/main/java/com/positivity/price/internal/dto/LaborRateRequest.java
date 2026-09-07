package com.positivity.price.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(
        description = "An hourly labor rate for a scope and a time window. A null locationId is the platform"
                + " default; a null operationCategory applies to every category.")
public class LaborRateRequest {

    @Schema(description = "Location this rate belongs to; omit for the platform default")
    private UUID locationId;

    @Size(max = 32)
    @Schema(
            description = "Operation category this rate applies to; omit to apply to every category",
            example = "TIRE_SERVICE",
            allowableValues = {"REPAIR", "DIAGNOSTIC", "MAINTENANCE", "TIRE_SERVICE"})
    private String operationCategory;

    @NotNull
    @Size(min = 3, max = 3)
    @Schema(description = "ISO 4217 currency", example = "USD")
    private String currency;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @Schema(description = "Hourly rate, greater than zero", example = "125.00")
    private BigDecimal hourlyRate;

    @NotNull
    @Schema(description = "Start of the effective window, inclusive")
    private Instant effectiveFrom;

    @Schema(description = "End of the effective window, exclusive; omit for open-ended")
    private Instant effectiveTo;
}
