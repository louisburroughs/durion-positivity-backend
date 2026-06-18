package com.positivity.customer.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a vehicle record for a party.
 * Issue #169: Vehicle: Create Vehicle Record with VIN and Description
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Request to create a vehicle record for a party")
public class CreateVehicleForPartyRequest {

    @NotBlank(message = "vinNumber is required")
    @Size(max = 17)
    @Schema(
            description = "Vehicle Identification Number (uniqueness scope global or per-party)",
            example = "1HGCM82633A004352",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String vinNumber;

    @Size(max = 64)
    @Schema(
            description = "Unit number or internal reference",
            example = "UNIT-42",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String unitNumber;

    @Size(max = 255)
    @Schema(
            description = "Vehicle description",
            example = "2019 Ford Transit 250 cargo van",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String description;

    @Size(max = 32)
    @Schema(
            description = "License plate (single string or plate plus region)",
            example = "ABC-1234",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String licensePlate;

    @Size(max = 64)
    @Schema(
            description = "License plate region/state",
            example = "CA",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String licensePlateRegion;
}
