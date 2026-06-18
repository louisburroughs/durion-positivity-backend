package com.positivity.bulkloader.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "A single column mapping to create or update for a bulk load job")
public class ColumnMappingUpdateRequest {

    @Schema(
            description = "Identifier of the existing mapping to update; omit to create a new mapping",
            example = "00000000-0000-0000-0000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID mappingId;

    @NotBlank
    @Schema(
            description = "Name of the source column in the uploaded file",
            example = "Product SKU",
            requiredMode = REQUIRED)
    private String sourceColumn;

    @NotBlank
    @Schema(
            description = "Name of the target domain field the column maps to",
            example = "sku",
            requiredMode = REQUIRED)
    private String targetField;
}
