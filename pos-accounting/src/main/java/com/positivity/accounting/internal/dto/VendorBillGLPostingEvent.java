package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Event published when a vendor bill is created and ready for GL posting.
 * Triggers journal entry creation with:
 * - Dr Inventory (for inventory items) or Dr Expense (for non-inventory items)
 * - Cr Accounts Payable
 *
 * This event is consumed by the posting engine to create the appropriate
 * journal entries based on active posting rules.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/130">Issue
 *      #130</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Vendor bill GL posting event for journal entry creation")
public class VendorBillGLPostingEvent {

    @NonNull
    @Schema(description = "Event UUID (idempotency key)", example = "01936e5c-1234-7a3d-8b6e-123456789012", requiredMode = REQUIRED)
    @JsonProperty("eventId")
    private UUID eventId;

    @NonNull
    @Schema(description = "Vendor bill UUID", example = "01936e5b-4567-7a3d-8b6e-1a2345678901", requiredMode = REQUIRED)
    @JsonProperty("vendorBillId")
    private UUID vendorBillId;

    @NonNull
    @Schema(description = "Vendor UUID", example = "01936e5a-7890-7a3d-8b6e-2b3456789012", requiredMode = REQUIRED)
    @JsonProperty("vendorId")
    private UUID vendorId;

    @NonNull
    @Schema(description = "Organization UUID", example = "00000000-0000-4000-a000-000000000010", requiredMode = REQUIRED)
    @JsonProperty("organizationId")
    private UUID organizationId;

    @Nullable
    @Schema(description = "Vendor name", example = "Acme Supplies Ltd", requiredMode = NOT_REQUIRED)
    @JsonProperty("vendorName")
    private String vendorName;

    @Nullable
    @Schema(description = "Purchase order UUID if available", example = "01936e59-abcd-7a3d-8b6e-3c4567890123", requiredMode = NOT_REQUIRED)
    @JsonProperty("purchaseOrderId")
    private UUID purchaseOrderId;

    @Nullable
    @Schema(description = "Purchase order number if available", example = "PO-2026-00123", requiredMode = NOT_REQUIRED)
    @JsonProperty("purchaseOrderNumber")
    private String purchaseOrderNumber;

    @NonNull
    @Schema(description = "Bill number", example = "BILL-2026-00456", requiredMode = REQUIRED)
    @JsonProperty("billNumber")
    private String billNumber;

    @NonNull
    @Schema(description = "Bill date (transaction date for posting)", example = "2026-01-15T10:30:00", requiredMode = REQUIRED)
    @JsonProperty("billDate")
    private LocalDateTime billDate;

    @NonNull
    @Schema(description = "Total bill amount", example = "1250.00", requiredMode = REQUIRED)
    @JsonProperty("totalAmount")
    private BigDecimal totalAmount;

    @NonNull
    @Schema(description = "Line items for GL distribution", requiredMode = REQUIRED)
    @JsonProperty("lineItems")
    private List<BillLineItem> lineItems;

    @Nullable
    @Schema(description = "Optional dimensional context for GL mapping", example = "{\"businessUnitId\":\"BU-001\"}", requiredMode = NOT_REQUIRED)
    @JsonProperty("dimensions")
    private Map<String, String> dimensions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Bill line item for GL account determination")
    public static class BillLineItem {

        @NonNull
        @Schema(description = "Product/SKU UUID", example = "01936e59-abcd-7a3d-8b6e-3c4567890123", requiredMode = REQUIRED)
        @JsonProperty("productId")
        private UUID productId;

        @Nullable
        @Schema(description = "SKU code", example = "WIDGET-A-001", requiredMode = NOT_REQUIRED)
        @JsonProperty("sku")
        private String sku;

        @Nullable
        @Schema(description = "Product description", example = "Widget Type A", requiredMode = NOT_REQUIRED)
        @JsonProperty("description")
        private String description;

        @NonNull
        @Schema(description = "Quantity", example = "100.00", requiredMode = REQUIRED)
        @JsonProperty("quantity")
        private BigDecimal quantity;

        @NonNull
        @Schema(description = "Unit price", example = "12.50", requiredMode = REQUIRED)
        @JsonProperty("unitPrice")
        private BigDecimal unitPrice;

        @NonNull
        @Schema(description = "Line total amount", example = "1250.00", requiredMode = REQUIRED)
        @JsonProperty("lineTotal")
        private BigDecimal lineTotal;

        @Schema(description = "Is this an inventory item (true) or expense item (false)", example = "true", requiredMode = REQUIRED)
        @JsonProperty("isInventoryItem")
        private boolean isInventoryItem;
    }
}
