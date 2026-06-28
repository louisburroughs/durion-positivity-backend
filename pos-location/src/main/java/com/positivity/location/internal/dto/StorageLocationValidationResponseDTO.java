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
@Schema(description = "Validation result for a storage location lookup")
public class StorageLocationValidationResponseDTO {

    @Schema(
            description = "Identifier of the storage location that was validated",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID storageLocationId;

    @Schema(
            description = "Identifier of the site that owns the storage location",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID siteId;

    @Schema(description = "Whether the storage location exists", example = "true", requiredMode = REQUIRED)
    private boolean exists;

    @Schema(description = "Whether the storage location is active", example = "true", requiredMode = REQUIRED)
    private boolean active;

    @Schema(description = "Maximum unit capacity of the storage location", example = "100", requiredMode = NOT_REQUIRED)
    private Integer maxUnitCapacity;
}
