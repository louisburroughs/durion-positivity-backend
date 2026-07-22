package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.catalog.internal.entity.ProductTrackingLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Request to set a product's stock tracking level")
public class ProductTrackingLevelUpdateRequestDto {

    @NotNull
    @Schema(
            description = "Stock tracking level",
            example = "LOT",
            implementation = ProductTrackingLevel.class,
            requiredMode = REQUIRED)
    private ProductTrackingLevel trackingLevel;
}
