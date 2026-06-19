package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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

    @Schema(description = "Whether the location is active", example = "true", requiredMode = REQUIRED)
    private boolean active;

    @Schema(
            description = "Identifier of the person responsible for the location",
            example = "1001",
            requiredMode = NOT_REQUIRED)
    private Long responsiblePersonId;

    @Schema(description = "Type classification of the location", requiredMode = NOT_REQUIRED)
    private LocationTypeDTO type;
}
