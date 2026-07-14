package com.positivity.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
    @NotNull
    @Schema(
            description = "Owning account identifier.",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID accountId;

    @NonNull
    @NotBlank
    @Schema(description = "Vehicle VIN.", requiredMode = Schema.RequiredMode.REQUIRED, example = "1HGCM82633A004352")
    private String vin;

    @Schema(
            description = "Fleet/unit number. Optional; stored as empty when omitted.",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "UNIT-1024")
    private String unitNumber;

    @Schema(
            description = "Human-readable vehicle description. Optional; stored as empty when omitted.",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "2024 Ford F-150 XL")
    private String description;

    @Schema(description = "License plate value.", example = "ABC1234", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String licensePlate;

    @Schema(
            description = "License plate jurisdiction/state or province.",
            example = "CA",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String licensePlateJurisdiction;

    // Optional structured fields
    @Schema(description = "Model year.", example = "2024", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Integer year;

    @Schema(description = "Vehicle make.", example = "Ford", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String make;

    @Schema(description = "Vehicle model.", example = "F-150", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String model;

    @Schema(description = "Vehicle trim.", example = "XL", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String trim;
}
