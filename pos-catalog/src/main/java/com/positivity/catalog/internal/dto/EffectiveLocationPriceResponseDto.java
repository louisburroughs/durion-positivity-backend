package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.catalog.internal.entity.PriceOverrideStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Effective location-specific price for a product")
public class EffectiveLocationPriceResponseDto {

    @NotNull
    @Schema(
            description = "Location identifier",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID locationId;

    @NotNull
    @Schema(
            description = "Product identifier",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = REQUIRED)
    private UUID productId;

    @NotNull
    @Schema(description = "Base price before any override", example = "100.00", requiredMode = REQUIRED)
    private BigDecimal basePrice;

    @NotNull
    @Schema(description = "Effective price after applying any override", example = "89.99", requiredMode = REQUIRED)
    private BigDecimal effectivePrice;

    @Schema(
            description = "Status of the override applied to derive the effective price",
            example = "ACTIVE",
            implementation = PriceOverrideStatus.class,
            requiredMode = NOT_REQUIRED)
    private PriceOverrideStatus overrideStatus;
}
