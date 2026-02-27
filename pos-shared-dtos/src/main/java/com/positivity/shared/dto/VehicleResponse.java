package com.positivity.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for vehicle operations - CAP:091.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Vehicle response payload.")
public class VehicleResponse {

    @Schema(description = "Vehicle identifier.", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID vehicleId;

    @Schema(description = "Owning account identifier.", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID accountId;

    @Schema(description = "Vehicle VIN.", example = "1HGCM82633A004352")
    private String vin;

    @Schema(description = "Normalized VIN used for lookup and uniqueness.", example = "1HGCM82633A004352")
    private String vinNormalized;

    @Schema(description = "Fleet/unit number.", example = "UNIT-1024")
    private String unitNumber;

    @Schema(description = "Human-readable vehicle description.", example = "2024 Ford F-150 XL")
    private String description;

    @Schema(description = "License plate value.", example = "ABC1234")
    private String licensePlate;

    @Schema(description = "License plate jurisdiction/state or province.", example = "CA")
    private String licensePlateJurisdiction;

    @Schema(description = "Model year.", example = "2024")
    private Integer year;

    @Schema(description = "Vehicle make.", example = "Ford")
    private String make;

    @Schema(description = "Vehicle model.", example = "F-150")
    private String model;

    @Schema(description = "Vehicle trim.", example = "XL")
    private String trim;

    @Schema(description = "Whether vehicle is active.", example = "true")
    private Boolean isActive;

    @Schema(description = "Record creation timestamp (UTC).", example = "2026-02-27T12:00:00Z")
    private Instant createdAt;

    @Schema(description = "Last update timestamp (UTC).", example = "2026-02-27T13:00:00Z")
    private Instant updatedAt;

    @Schema(description = "Optimistic lock version.", example = "1")
    private Long version;
}
