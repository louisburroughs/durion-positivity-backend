package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.catalog.internal.entity.DimensionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Product dimension detail")
public class DimensionDto {

    @Schema(
            description = "Dimension identifier",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID id;

    @Schema(
            description = "Dimension type",
            example = "LENGTH",
            implementation = DimensionType.class,
            requiredMode = REQUIRED)
    private DimensionType dimensionType;

    @Schema(description = "Dimension description", example = "Length", requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(description = "Unit of measure", example = "in", requiredMode = NOT_REQUIRED)
    private String unitOfMeasure;

    @Schema(description = "Dimension value", example = "12.5", requiredMode = REQUIRED)
    private double dimensionValue;
}
