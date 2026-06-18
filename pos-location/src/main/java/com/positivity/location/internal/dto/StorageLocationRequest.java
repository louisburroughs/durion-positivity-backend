package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.positivity.location.internal.enums.StorageLocationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for creating storage locations.
 *
 * Issue: CAP-214 #39
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request payload for creating a storage location")
public class StorageLocationRequest {

    @Schema(description = "Display name of the storage location", example = "Aisle 3 Bin 12", requiredMode = REQUIRED)
    @NotBlank
    private String name;

    @Schema(description = "Barcode identifying the storage location", example = "SL-000312", requiredMode = NOT_REQUIRED)
    private String barcode;

    @Schema(description = "Type classification of the storage location", example = "BIN", requiredMode = REQUIRED)
    @NotNull
    private StorageLocationType type;

    @Schema(
            description = "Identifier of the parent storage location",
            example = "01960003-0000-7000-8000-000000000001",
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
}
