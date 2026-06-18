package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.location.internal.enums.StorageLocationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for storage location endpoints.
 *
 * Issue: CAP-214 #39
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload describing a storage location")
public class StorageLocationResponse {

    @Schema(
            description = "Unique identifier of the storage location",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(description = "Display name of the storage location", example = "Aisle 3 Bin 12", requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(description = "Barcode identifying the storage location", example = "SL-000312", requiredMode = NOT_REQUIRED)
    private String barcode;

    @Schema(description = "Type classification of the storage location", example = "BIN", requiredMode = NOT_REQUIRED)
    private StorageLocationType type;

    @Schema(description = "Operational status of the storage location", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(
            description = "Identifier of the site that owns the storage location",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID siteId;

    @Schema(
            description = "Identifier of the parent storage location",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID parentStorageLocationId;

    @Schema(
            description = "Capacity attributes of the storage location",
            example = "{\"maxUnits\": 100}",
            requiredMode = NOT_REQUIRED)
    private Map<String, Object> capacity;

    @Schema(
            description = "Temperature attributes of the storage location",
            example = "{\"min\": 2, \"max\": 8, \"unit\": \"C\"}",
            requiredMode = NOT_REQUIRED)
    private Map<String, Object> temperature;

    @Schema(description = "Count of inventory items stored at the location", example = "42", requiredMode = REQUIRED)
    private int inventoryCount;
}
