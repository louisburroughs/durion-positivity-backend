package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.location.internal.enums.StorageLocationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight flat projection of a storage location for topology consumers
 * (e.g. pos-inventory rollup). Includes every status; never paginated.
 *
 * Issue: CAP-214 #655
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Lightweight flat projection of a storage location for topology consumers")
public class StorageLocationTopologyResponse {

    @Schema(
            description = "Unique identifier of the storage location",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(
            description = "Display name of the storage location",
            example = "Aisle 3 Bin 12",
            requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(description = "Type classification of the storage location", example = "BIN", requiredMode = NOT_REQUIRED)
    private StorageLocationType type;

    @Schema(description = "Operational status of the storage location", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(
            description = "Identifier of the parent storage location",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID parentStorageLocationId;
}
