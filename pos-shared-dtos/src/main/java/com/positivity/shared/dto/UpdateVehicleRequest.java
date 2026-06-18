package com.positivity.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating mutable vehicle fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for partially updating mutable vehicle fields.")
public class UpdateVehicleRequest {

    @Size(max = 255)
    @Schema(
            description = "Fleet/unit number.",
            example = "UNIT-2048",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String unitNumber;

    @Size(max = 4000)
    @Schema(
            description = "Human-readable vehicle description.",
            example = "2023 Chevrolet Silverado 1500",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String description;

    @Size(max = 50)
    @Schema(description = "License plate value.", example = "XYZ7890", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String licensePlate;

    @Size(max = 50)
    @Schema(
            description = "License plate jurisdiction/state or province.",
            example = "TX",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String licensePlateJurisdiction;

    @Min(1886)
    @Max(2100)
    @Schema(description = "Model year.", example = "2023", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Integer year;

    @Size(max = 255)
    @Schema(description = "Vehicle make.", example = "Chevrolet", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String make;

    @Size(max = 255)
    @Schema(
            description = "Vehicle model.",
            example = "Silverado 1500",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String model;

    @Size(max = 255)
    @Schema(description = "Vehicle trim.", example = "LT", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String trim;

    @AssertTrue(message = "At least one field must be provided for update")
    private boolean isAtLeastOneFieldProvided() {
        return unitNumber != null
                || description != null
                || licensePlate != null
                || licensePlateJurisdiction != null
                || year != null
                || make != null
                || model != null
                || trim != null;
    }

    @AssertTrue(message = "unitNumber must not be blank when provided")
    private boolean isUnitNumberNotBlankWhenProvided() {
        return unitNumber == null || !unitNumber.isBlank();
    }

    @AssertTrue(message = "description must not be blank when provided")
    private boolean isDescriptionNotBlankWhenProvided() {
        return description == null || !description.isBlank();
    }
}
