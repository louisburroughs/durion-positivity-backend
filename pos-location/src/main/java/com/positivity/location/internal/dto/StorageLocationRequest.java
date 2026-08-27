package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.positivity.location.internal.enums.AllowNewProductPolicy;
import com.positivity.location.internal.enums.StorageCategory;
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

    @Schema(
            description = "Barcode identifying the storage location",
            example = "SL-000312",
            requiredMode = NOT_REQUIRED)
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
            description = "Putaway capability of the storage location — what it is fit to hold, independent of the"
                    + " physical type. Omit to leave it undeclared, which reads back as GENERAL (accepts every"
                    + " catalog category).",
            example = "TIRE_RACK",
            requiredMode = NOT_REQUIRED)
    private StorageCategory storageCategoryCode;

    @Schema(
            description = "Whether the storage location provides spill/hazard containment; required by battery and"
                    + " oil storage capabilities. Defaults to false.",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private boolean hazardContainment;

    @Schema(
            description = "Whether the storage location will take stock of a product it is not already holding."
                    + " Defaults to MIXED.",
            example = "MIXED",
            requiredMode = NOT_REQUIRED)
    private AllowNewProductPolicy allowNewProduct;

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
