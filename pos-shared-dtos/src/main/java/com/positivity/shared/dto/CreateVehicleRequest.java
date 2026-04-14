package com.positivity.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * Request DTO for creating a vehicle - CAP:091 Story #105.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for creating a vehicle record.")
public class CreateVehicleRequest {

    @NonNull
    @Schema(
            description = "Owning account identifier.",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID accountId;

    @NonNull
    @Schema(description = "Vehicle VIN.", requiredMode = Schema.RequiredMode.REQUIRED, example = "1HGCM82633A004352")
    private String vin;

    @NonNull
    @Schema(description = "Fleet/unit number.", requiredMode = Schema.RequiredMode.REQUIRED, example = "UNIT-1024")
    private String unitNumber;

    @NonNull
    @Schema(
            description = "Human-readable vehicle description.",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2024 Ford F-150 XL")
    private String description;

    @Schema(description = "License plate value.", example = "ABC1234")
    private String licensePlate;

    @Schema(description = "License plate jurisdiction/state or province.", example = "CA")
    private String licensePlateJurisdiction;

    // Optional structured fields
    @Schema(description = "Model year.", example = "2024")
    private Integer year;

    @Schema(description = "Vehicle make.", example = "Ford")
    private String make;

    @Schema(description = "Vehicle model.", example = "F-150")
    private String model;

    @Schema(description = "Vehicle trim.", example = "XL")
    private String trim;
}
