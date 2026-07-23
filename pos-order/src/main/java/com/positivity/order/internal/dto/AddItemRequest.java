package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request payload for adding an item line to a cart")
public class AddItemRequest {

    @Schema(
            description = "Stock keeping unit identifying the item to add",
            example = "SKU-OIL-5W30-1L",
            requiredMode = REQUIRED)
    @NotBlank
    private String itemSku;

    @Schema(description = "Quantity of the item to add to the cart", example = "2", requiredMode = REQUIRED)
    @Min(1)
    private int quantity;

    @Schema(
            description = "Reason code justifying a manual price or special handling",
            example = "PRICE_MATCH",
            requiredMode = NOT_REQUIRED)
    private String reasonCode;

    @Schema(
            description = "Manual unit price override for the item, when applicable",
            example = "19.99",
            requiredMode = NOT_REQUIRED)
    private BigDecimal manualPrice;

    @Schema(
            description = "Client-supplied stable line identity; a replayed add with a known lineUuid updates the"
                    + " existing line instead of duplicating it",
            example = "01960003-0000-7000-8000-0000000000aa",
            requiredMode = NOT_REQUIRED)
    private UUID lineUuid;

    @Schema(description = "Customer-visible line note", example = "Front wipers only", requiredMode = NOT_REQUIRED)
    private String customerNote;

    @Schema(
            description = "Shop-internal line note",
            example = "Price-matched to invoice #123",
            requiredMode = NOT_REQUIRED)
    private String internalNote;
}
