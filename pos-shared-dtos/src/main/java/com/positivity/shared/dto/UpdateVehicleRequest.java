package com.positivity.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "Fleet/unit number.", example = "UNIT-2048")
    private String unitNumber;

    @Schema(description = "Human-readable vehicle description.", example = "2023 Chevrolet Silverado 1500")
    private String description;

    @Schema(description = "License plate value.", example = "XYZ7890")
    private String licensePlate;

    @Schema(description = "License plate jurisdiction/state or province.", example = "TX")
    private String licensePlateJurisdiction;

    @Schema(description = "Model year.", example = "2023")
    private Integer year;

    @Schema(description = "Vehicle make.", example = "Chevrolet")
    private String make;

    @Schema(description = "Vehicle model.", example = "Silverado 1500")
    private String model;

    @Schema(description = "Vehicle trim.", example = "LT")
    private String trim;
}
