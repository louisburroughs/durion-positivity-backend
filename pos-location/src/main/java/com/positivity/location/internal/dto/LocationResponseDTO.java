package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload describing a location")
public class LocationResponseDTO {

    @Schema(
            description = "Unique identifier of the location",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(description = "Display name of the location", example = "Downtown Service Center", requiredMode = REQUIRED)
    @NotNull
    private String name;

    @Schema(description = "Unique business code of the location", example = "LOC-001", requiredMode = NOT_REQUIRED)
    private String code;

    @Schema(
            description = "Identifier of the associated geographical location",
            example = "01960003-0000-7000-8000-000000000002",
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

    @Schema(
            description = "Primary phone number for the location",
            example = "+1-217-555-0100",
            requiredMode = NOT_REQUIRED)
    private String phoneNumber;

    @Schema(description = "Whether the location is active", example = "true", requiredMode = REQUIRED)
    private boolean active;

    @Schema(
            description = "People-contact person identifier of the person responsible for the location",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID responsiblePersonId;

    @Schema(description = "Type classification of the location", requiredMode = NOT_REQUIRED)
    private LocationTypeDTO type;

    // Issue #2139: the three scheduling facts were write-only on create/update/patch. A caller
    // could publish hours, be refused a booking against them, and never read back what the server
    // stored or which zone it reads them in — and the zone is what decides whether 08:00 means
    // 08:00 to the shop or to the caller (DECISION-015: hours are facility-local).
    @Schema(
            description = "IANA timezone identifier of the location; the zone every operating-hours and holiday "
                    + "closure entry below is expressed in, and the zone scheduling converts a booking into",
            example = "America/New_York",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String timezone;

    @Schema(
            description = "Stored weekly operating hours, one entry per published day of the week, in the "
                    + "location's own timezone; null when hours have never been published",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private List<OperatingHoursResponse> operatingHours;

    @Schema(
            description = "Stored dated closures, in the location's own timezone; null when none have ever been "
                    + "published",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private List<HolidayClosureResponse> holidayClosures;

    // Issue #1657: computed per request from aggregate queries over bays and mobile
    // units; never stored on the location row. An inactive location always reports
    // false and zero counts.
    @Schema(
            description = "Whether the location can perform repairs, true when it has at least one ACTIVE bay "
                    + "or at least one ACTIVE mobile unit based there; always false for an inactive location",
            example = "true",
            requiredMode = REQUIRED)
    private boolean hasRepairCapability;

    @Schema(
            description = "Number of bays owned by the location with status ACTIVE; OUT_OF_SERVICE bays are "
                    + "excluded and an inactive location always reports 0",
            example = "3",
            requiredMode = REQUIRED)
    private int activeBayCount;

    @Schema(
            description = "Number of mobile units based at the location with status ACTIVE; INACTIVE units are "
                    + "excluded and an inactive location always reports 0",
            example = "1",
            requiredMode = REQUIRED)
    private int activeMobileUnitCount;
}
