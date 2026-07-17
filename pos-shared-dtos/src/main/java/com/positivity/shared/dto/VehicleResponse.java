package com.positivity.shared.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for vehicle operations - CAP:091.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Vehicle response payload.")
public class VehicleResponse {

    @Schema(
            description = "Vehicle identifier.",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    @NotNull
    private UUID vehicleId;

    @Schema(
            description = "Owning account identifier.",
            example = "550e8400-e29b-41d4-a716-446655440001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID accountId;

    @Schema(description = "Vehicle VIN.", example = "1HGCM82633A004352", requiredMode = REQUIRED)
    @NotNull
    private String vin;

    @Schema(
            description = "Normalized VIN used for lookup and uniqueness.",
            example = "1HGCM82633A004352",
            requiredMode = REQUIRED)
    @NotNull
    private String vinNormalized;

    @Schema(description = "Fleet/unit number.", example = "UNIT-1024", requiredMode = REQUIRED)
    @NotNull
    private String unitNumber;

    @Schema(
            description = "Human-readable vehicle description.",
            example = "2024 Ford F-150 XL",
            requiredMode = REQUIRED)
    @NotNull
    private String description;

    @Schema(description = "License plate value.", example = "ABC1234", requiredMode = NOT_REQUIRED)
    private String licensePlate;

    @Schema(description = "License plate jurisdiction/state or province.", example = "CA", requiredMode = NOT_REQUIRED)
    private String licensePlateJurisdiction;

    @Schema(description = "Model year.", example = "2024", requiredMode = NOT_REQUIRED)
    private Integer year;

    @Schema(description = "Vehicle make.", example = "Ford", requiredMode = NOT_REQUIRED)
    private String make;

    @Schema(description = "Vehicle model.", example = "F-150", requiredMode = NOT_REQUIRED)
    private String model;

    @Schema(description = "Vehicle trim.", example = "XL", requiredMode = NOT_REQUIRED)
    private String trim;

    @Schema(description = "Last recorded odometer reading value.", example = "45210", requiredMode = NOT_REQUIRED)
    private Integer odometerValue;

    @Schema(
            description = "Unit of the odometer reading (MILES or KILOMETERS).",
            example = "MILES",
            allowableValues = {"MILES", "KILOMETERS"},
            requiredMode = NOT_REQUIRED)
    private String odometerUnit;

    @Schema(description = "Whether vehicle is active.", example = "true", requiredMode = REQUIRED)
    @NotNull
    private Boolean isActive;

    @Schema(description = "Record creation timestamp (UTC).", example = "2026-02-27T12:00:00Z", requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(description = "Last update timestamp (UTC).", example = "2026-02-27T13:00:00Z", requiredMode = REQUIRED)
    @NotNull
    private Instant updatedAt;

    @Schema(
            description = "Username of the actor who created this record.",
            example = "jsmith",
            requiredMode = NOT_REQUIRED)
    private String createdBy;

    @Schema(
            description = "Username of the actor who last modified this record.",
            example = "jsmith",
            requiredMode = NOT_REQUIRED)
    private String updatedBy;

    @Schema(description = "Optimistic lock version.", example = "1", requiredMode = REQUIRED)
    @NotNull
    private Long version;
}
