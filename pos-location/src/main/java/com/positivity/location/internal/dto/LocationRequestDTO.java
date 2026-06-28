package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request payload for creating or updating a location")
public class LocationRequestDTO {

    @Schema(description = "Display name of the location", example = "Downtown Service Center", requiredMode = REQUIRED)
    @NotBlank(message = "name is required")
    private String name;

    @Schema(description = "Unique business code of the location", example = "LOC-001", requiredMode = REQUIRED)
    @NotBlank(message = "code is required")
    private String code;

    @Schema(
            description = "Identifier of the associated geographical location",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID geographicalLocationId;

    @Schema(description = "First line of the street address", example = "123 Main St", requiredMode = NOT_REQUIRED)
    private String addressLine1;

    @Schema(description = "Second line of the street address", example = "Suite 200", requiredMode = NOT_REQUIRED)
    private String addressLine2;

    @Schema(description = "City of the location", example = "Springfield", requiredMode = NOT_REQUIRED)
    private String city;

    @Schema(description = "State or province of the location", example = "IL", requiredMode = NOT_REQUIRED)
    private String state;

    @Schema(description = "Postal or ZIP code of the location", example = "62704", requiredMode = NOT_REQUIRED)
    private String postalCode;

    @Schema(description = "Country of the location", example = "US", requiredMode = NOT_REQUIRED)
    private String country;

    @Schema(description = "Mailing address of the location", example = "PO Box 100", requiredMode = NOT_REQUIRED)
    private String mailingAddress;

    @Schema(description = "Whether the location is active", example = "true", requiredMode = NOT_REQUIRED)
    private Boolean active;

    @Schema(
            description = "Identifier of the person responsible for the location",
            example = "1001",
            requiredMode = NOT_REQUIRED)
    private Long responsiblePersonId;

    @Schema(
            description = "IANA timezone identifier for the location",
            example = "America/Chicago",
            requiredMode = NOT_REQUIRED)
    private String timezone;

    @Schema(description = "Weekly operating hours for the location", requiredMode = NOT_REQUIRED)
    @Valid
    private List<OperatingHoursRequest> operatingHours;

    @Schema(description = "Holiday closures for the location", requiredMode = NOT_REQUIRED)
    @Valid
    private List<HolidayClosureRequest> holidayClosures;

    @Schema(
            description = "Buffer minutes reserved before appointments for check-in",
            example = "15",
            requiredMode = NOT_REQUIRED)
    @PositiveOrZero
    private Integer checkInBufferMinutes;

    @Schema(
            description = "Buffer minutes reserved after appointments for cleanup",
            example = "10",
            requiredMode = NOT_REQUIRED)
    @PositiveOrZero
    private Integer cleanupBufferMinutes;

    @Schema(description = "Type classification of the location", requiredMode = REQUIRED)
    @NotNull(message = "type is required")
    @Valid
    private LocationTypeDTO type;

    @Schema(
            description = "Map of parent relationships keyed by relationship type",
            example = "{\"ORGANIZATIONAL\": \"01960003-0000-7000-8000-000000000002\"}",
            requiredMode = NOT_REQUIRED)
    private Map<String, Object> parents;
}
