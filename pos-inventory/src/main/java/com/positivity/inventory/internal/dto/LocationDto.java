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

@Schema(description = "Representation of an inventory location")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationDto {

    @Schema(
            description = "Unique identifier of the location",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(
            description = "Identifier of the site the location belongs to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID siteId;

    @Schema(description = "Human-readable name of the location", example = "Main Warehouse", requiredMode = REQUIRED)
    @NotNull
    private String name;

    @Schema(description = "Type classification of the location", example = "WAREHOUSE", requiredMode = NOT_REQUIRED)
    private String type;

    @Schema(description = "Operational status of the location", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(
            description = "IANA timezone identifier of the location",
            example = "America/New_York",
            requiredMode = NOT_REQUIRED)
    private String timezone;

    @Schema(description = "Whether the location is currently active", example = "true", requiredMode = REQUIRED)
    private boolean active;
}
