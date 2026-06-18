package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request payload for creating a location price override")
public class LocationPriceOverrideCreateRequestDto {

    @NotNull
    @Schema(
            description = "Location identifier the override applies to",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID locationId;

    @NotNull
    @Schema(
            description = "Product identifier the override applies to",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = REQUIRED)
    private UUID productId;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @Schema(description = "Base price before override", example = "100.00", requiredMode = REQUIRED)
    private BigDecimal basePrice;

    @DecimalMin(value = "0.0")
    @Schema(description = "Item cost used for margin calculation", example = "60.00", requiredMode = NOT_REQUIRED)
    private BigDecimal cost;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @Schema(description = "Requested override price", example = "89.99", requiredMode = REQUIRED)
    private BigDecimal overridePrice;

    @NotNull
    @Schema(
            description = "Identifier of the user creating the override",
            example = "8b8df63e-18d8-4bde-a8f4-88bc36bc57d7",
            requiredMode = REQUIRED)
    private UUID createdByUserId;
}
