package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.entity.DimensionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Product dimension detail")
public class DimensionDto {

    @Schema(description = "Dimension identifier", example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535")
    private UUID id;

    @Schema(description = "Dimension type", implementation = DimensionType.class)
    private DimensionType dimensionType;

    @Schema(description = "Dimension description", example = "Length")
    private String description;

    @Schema(description = "Unit of measure", example = "in")
    private String unitOfMeasure;

    @Schema(description = "Dimension value", example = "12.5")
    private double dimensionValue;
}
