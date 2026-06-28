package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Single record within a bulk location ingest request.
 */
@Data
@Schema(description = "A single location record within a bulk ingest request")
public class LocationBulkIngestRecord {

    @Schema(description = "Display name of the location", example = "Downtown Service Center", requiredMode = REQUIRED)
    @NotBlank
    private String name;

    @Schema(description = "Unique business code of the location", example = "LOC-001", requiredMode = REQUIRED)
    @NotBlank
    private String code;

    @Schema(description = "First line of the street address", example = "123 Main St", requiredMode = NOT_REQUIRED)
    private String addressLine1;

    @Schema(description = "Second line of the street address", example = "Suite 200", requiredMode = NOT_REQUIRED)
    private String addressLine2;

    @Schema(description = "City of the location", example = "Springfield", requiredMode = NOT_REQUIRED)
    private String city;

    @Schema(description = "State or province of the location", example = "IL", requiredMode = NOT_REQUIRED)
    private String stateOrProvince;

    @Schema(description = "Postal or ZIP code of the location", example = "62704", requiredMode = NOT_REQUIRED)
    private String postalCode;

    @Schema(description = "ISO 3166-1 alpha-2 country code", example = "US", requiredMode = NOT_REQUIRED)
    private String countryCode;

    @Schema(
            description = "Primary phone number for the location",
            example = "+1-217-555-0100",
            requiredMode = NOT_REQUIRED)
    private String phoneNumber;

    @Schema(description = "Whether the location is active", example = "true", requiredMode = NOT_REQUIRED)
    private Boolean active;

    @Schema(
            description = "Name of the location type to resolve during ingest",
            example = "STORE",
            requiredMode = NOT_REQUIRED)
    private String locationTypeName;
}
