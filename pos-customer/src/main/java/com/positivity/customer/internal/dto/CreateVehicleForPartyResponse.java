package com.positivity.customer.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for creating a vehicle record for a party.
 * Issue #169: Vehicle: Create Vehicle Record with VIN and Description
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Response returned after creating a vehicle record for a party")
public class CreateVehicleForPartyResponse {

    /**
     * Newly created vehicle ID
     */
    @NotBlank
    @Schema(
            description = "Newly created vehicle identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String vehicleId;

    /**
     * Party ID (owner) of the vehicle
     */
    @NotBlank
    @Schema(
            description = "Party identifier of the vehicle owner",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String partyId;

    /**
     * Vehicle Identification Number
     */
    @Schema(
            description = "Vehicle Identification Number",
            example = "1HGCM82633A004352",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String vinNumber;

    /**
     * Unit number or internal reference
     */
    @Schema(
            description = "Unit number or internal reference",
            example = "UNIT-204",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String unitNumber;

    /**
     * Vehicle description
     */
    @Schema(
            description = "Human-readable vehicle description",
            example = "2020 Ford F-150 XLT",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String description;

    /**
     * License plate
     */
    @Schema(description = "License plate", example = "ABC-1234", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String licensePlate;

    /**
     * Vehicle status (ACTIVE|INACTIVE)
     */
    @NotBlank
    @Schema(
            description = "Vehicle status",
            example = "ACTIVE",
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"ACTIVE", "INACTIVE"})
    private String status;

    /**
     * Timestamp of creation (ISO 8601)
     */
    @Schema(
            description = "Timestamp of creation (ISO 8601)",
            example = "2026-06-17T10:15:30Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String createdAt;
}
