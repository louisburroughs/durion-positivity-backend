package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Representation of a storage location (bin or slot) within an inventory location")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageLocationDto {

    @Schema(
            description = "Unique identifier of the storage location",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID storageLocationId;

    @Schema(
            description = "Identifier of the parent location the storage location belongs to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(description = "Code identifying the storage location", example = "LOC-001", requiredMode = REQUIRED)
    @NotNull
    private String code;

    @Schema(description = "Maximum storage capacity of the location", example = "100", requiredMode = REQUIRED)
    private int capacity;

    @Schema(
            description = "Whether the storage location is currently active",
            example = "true",
            requiredMode = REQUIRED)
    private boolean active;
}
