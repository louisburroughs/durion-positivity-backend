package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response representing a single line on a sales order")
public class SalesOrderLineResponse {

    @Schema(
            description = "Unique identifier of the order line",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private String orderLineId;

    @Schema(
            description = "Stock keeping unit identifying the line item",
            example = "SKU-OIL-5W30-1L",
            requiredMode = REQUIRED)
    @NotNull
    private String itemSku;

    @Schema(
            description = "Human-readable description of the line item",
            example = "Synthetic Motor Oil 5W-30 1L",
            requiredMode = NOT_REQUIRED)
    private String itemDescription;

    @Schema(description = "Quantity of the item on the line", example = "2", requiredMode = REQUIRED)
    private int quantity;

    @Schema(description = "Unit price applied to the line", example = "19.99", requiredMode = NOT_REQUIRED)
    private BigDecimal unitPrice;

    @Schema(description = "Fulfillment status of the line", example = "PENDING", requiredMode = NOT_REQUIRED)
    private String fulfillmentStatus;

    @Schema(
            description = "Source of the line price (e.g. CATALOG, MANUAL, OVERRIDE)",
            example = "CATALOG",
            requiredMode = NOT_REQUIRED)
    private String priceSource;

    @Schema(
            description = "Reason code associated with a manual price or special handling",
            example = "PRICE_MATCH",
            requiredMode = NOT_REQUIRED)
    private String reasonCode;

    @Schema(
            description = "Type classification of the line's originating source",
            example = "WORKORDER",
            requiredMode = NOT_REQUIRED)
    private String sourceType;

    @Schema(
            description = "Identifier of the originating source record",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    private String sourceId;

    @Schema(
            description = "Identifier of the originating source line",
            example = "01960003-0000-7000-8000-000000000006",
            requiredMode = NOT_REQUIRED)
    private String sourceLineId;
}
