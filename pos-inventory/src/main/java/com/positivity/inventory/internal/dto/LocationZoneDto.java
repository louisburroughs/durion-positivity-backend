package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Representation of a zone within an inventory location")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationZoneDto {

    @Schema(
            description = "Unique identifier of the zone",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID zoneId;

    @Schema(
            description = "Identifier of the location the zone belongs to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(description = "Human-readable name of the zone", example = "Cold Storage A", requiredMode = REQUIRED)
    @NotNull
    private String zoneName;

    @Schema(
            description = "Optional descriptive text for the zone",
            example = "Refrigerated zone for perishable goods",
            requiredMode = NOT_REQUIRED)
    private String description;
}
