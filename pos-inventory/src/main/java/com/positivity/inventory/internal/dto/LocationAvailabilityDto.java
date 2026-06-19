package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-location availability projection for inventory availability queries.
 *
 * Issue: CAP-170 (#48)
 */
@Schema(description = "Per-location availability projection for inventory availability queries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationAvailabilityDto {

    @Schema(
            description = "Identifier of the location",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(
            description = "Human-readable name of the location",
            example = "Main Warehouse",
            requiredMode = REQUIRED)
    @NotNull
    private String locationName;

    @Schema(description = "On-hand quantity at the location", example = "120", requiredMode = REQUIRED)
    private int onHandQuantity;

    @Schema(
            description = "Available-to-promise quantity at the location",
            example = "90",
            requiredMode = REQUIRED)
    private int availableToPromiseQuantity;
}
