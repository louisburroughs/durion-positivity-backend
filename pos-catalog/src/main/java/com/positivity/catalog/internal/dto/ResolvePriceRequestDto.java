package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request to resolve the effective price for a product in a given context")
public class ResolvePriceRequestDto {

    @NotNull
    @Schema(
            description = "Identifier of the product to price",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID productId;

    @Schema(
            description = "Identifier of a specific price book to resolve against (optional)",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = NOT_REQUIRED)
    private UUID priceBookId;

    @Schema(
            description = "Identifier of the location used to scope price book selection",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(description = "Customer tier used to scope price book selection", example = "GOLD", requiredMode = NOT_REQUIRED)
    private String customerTier;

    @Schema(description = "ISO currency code for the resolved price", example = "USD", requiredMode = NOT_REQUIRED)
    private String currency;

    @Schema(
            description = "Date for which the price should be resolved (defaults to today when omitted)",
            example = "2026-06-18",
            requiredMode = NOT_REQUIRED)
    private LocalDate asOf;
}
