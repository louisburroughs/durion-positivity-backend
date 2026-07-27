package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.catalog.internal.entity.ProductUomType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "A per-product unit-of-measure conversion to the product's base UoM")
public class ProductUomDto {

    @Schema(
            description = "Identifier of the product UoM row",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID id;

    @Schema(
            description = "Identifier of the product",
            example = "018e1c9f-6b5a-7890-abcd-1234567890ab",
            requiredMode = REQUIRED)
    private UUID productId;

    @Schema(description = "Unit-of-measure code", example = "CASE", requiredMode = REQUIRED)
    private String uomCode;

    @Schema(
            description = "Role of this UoM for the product",
            example = "PURCHASE",
            implementation = ProductUomType.class,
            requiredMode = REQUIRED)
    private ProductUomType uomType;

    @Schema(
            description = "Multiplier converting one unit of this UoM into the product's base UoM",
            example = "12",
            requiredMode = REQUIRED)
    private BigDecimal factorToBase;

    @Schema(
            description = "Decimal precision scale for quantities expressed in this UoM",
            example = "0",
            requiredMode = REQUIRED)
    private int precisionScale;

    @Schema(description = "Created timestamp", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(description = "Updated timestamp", example = "2026-01-16T11:00:00Z", requiredMode = NOT_REQUIRED)
    private Instant updatedAt;
}
