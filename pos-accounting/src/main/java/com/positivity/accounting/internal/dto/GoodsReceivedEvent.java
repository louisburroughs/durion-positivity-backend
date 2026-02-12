package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Event published when goods are received from a vendor.
 * Triggers vendor bill creation in PENDING_RECEIPT_MATCH status.
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/130">Issue
 *      #130</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Goods received event from inventory/purchasing system")
public class GoodsReceivedEvent {

    @NotNull
    @Schema(description = "Event UUID (idempotency key)", example = "01936e5c-1234-7a3d-8b6e-123456789012", required = true)
    @JsonProperty("eventId")
    private UUID eventId;

    @NotNull
    @Schema(description = "Purchase order UUID", example = "01936e5b-4567-7a3d-8b6e-1a2345678901", required = true)
    @JsonProperty("purchaseOrderId")
    private UUID purchaseOrderId;

    @NotNull
    @Schema(description = "Vendor UUID", example = "01936e5a-7890-7a3d-8b6e-2b3456789012", required = true)
    @JsonProperty("vendorId")
    private UUID vendorId;

    @Nullable
    @Schema(description = "Vendor name", example = "Acme Supplies Ltd")
    @JsonProperty("vendorName")
    private String vendorName;

    @NotNull
    @Schema(description = "Date goods were received", example = "2026-01-15T10:30:00", required = true)
    @JsonProperty("receivedDate")
    private LocalDateTime receivedDate;

    @NotNull
    @Schema(description = "Line items received", required = true)
    @JsonProperty("lineItems")
    private List<ReceivedLineItem> lineItems;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Line item received from vendor")
    public static class ReceivedLineItem {

        @NotNull
        @Schema(description = "Product/SKU UUID", example = "01936e59-abcd-7a3d-8b6e-3c4567890123", required = true)
        @JsonProperty("productId")
        private UUID productId;

        @NotBlank
        @Schema(description = "Product description", example = "Widget Type A", required = true)
        @JsonProperty("description")
        private String description;

        @NotNull
        @Positive
        @Schema(description = "Quantity received", example = "100.00", required = true)
        @JsonProperty("quantity")
        private BigDecimal quantity;

        @NotNull
        @Positive
        @Schema(description = "Unit price from PO", example = "12.50", required = true)
        @JsonProperty("unitPrice")
        private BigDecimal unitPrice;

        @Schema(description = "Is this an inventory item (true) or expense item (false)", example = "true")
        @JsonProperty("isInventoryItem")
        private boolean isInventoryItem = true;
    }
}
