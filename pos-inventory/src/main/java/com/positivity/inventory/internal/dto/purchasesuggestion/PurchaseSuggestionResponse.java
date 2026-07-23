package com.positivity.inventory.internal.dto.purchasesuggestion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "A scan-generated suggestion to purchase stock for a PURCHASE-preferring replenishment"
                + " policy. Lifecycle SUGGESTED → ACCEPTED → CONVERTED | DISMISSED; acceptance is human-mandatory and"
                + " conversion produces a DRAFT purchase order subject to the normal approval workflow")
public class PurchaseSuggestionResponse {

    @Schema(
            description = "Purchase suggestion identifier",
            example = "01980003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID suggestionId;

    @Schema(
            description = "Replenishment policy the suggestion was computed for",
            example = "01980003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    private UUID policyId;

    @Schema(description = "Stock item (SKU) to purchase", example = "SKU-12345", requiredMode = REQUIRED)
    private String itemSKU;

    @Schema(
            description = "Destination location the purchased stock is for",
            example = "01980003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Quantity to purchase after MOQ/pack/order-multiple rounding",
            example = "24",
            requiredMode = REQUIRED)
    private Integer suggestedQuantity;

    @Schema(
            description = "Which normalized vendor feed the selected vendor came from; null when no vendor"
                    + " was selectable",
            example = "MANUFACTURER",
            allowableValues = {"MANUFACTURER", "DISTRIBUTOR"},
            requiredMode = NOT_REQUIRED)
    private String suggestedVendorType;

    @Schema(
            description = "Selected manufacturer or distributor identifier; null when no vendor was selectable",
            example = "01980003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    private UUID vendorRefId;

    @Schema(
            description = "Per-unit cost in minor currency units from the vendor feed; null when the feed"
                    + " carries no price",
            example = "1499",
            requiredMode = NOT_REQUIRED)
    private Long unitCostMinor;

    @Schema(description = "ISO 4217 currency of unitCostMinor", example = "USD", requiredMode = NOT_REQUIRED)
    private String unitCostCurrency;

    @Schema(
            description = "Selected vendor's pack size captured at suggestion time",
            example = "6",
            requiredMode = NOT_REQUIRED)
    private Integer vendorPackSize;

    @Schema(
            description = "Vendor lead-time estimate in days (MAX-day estimate, consistent with the"
                    + " replenishment horizon math)",
            example = "5",
            requiredMode = NOT_REQUIRED)
    private Integer leadTimeDays;

    @Schema(
            description = "Earliest date the purchase could plausibly arrive (suggestion date + lead time)",
            example = "2026-08-01",
            requiredMode = NOT_REQUIRED)
    private LocalDate earliestExpectedDate;

    @Schema(
            description = "Explainable record of the deterministic vendor-ranking inputs",
            example = "MANUFACTURER 0198…: avail 40>=12, lead 5d, price 1499 USD-minor/unit; beat DISTRIBUTOR"
                    + " 0199…: avail 30>=12, lead 7d, price n/a",
            requiredMode = REQUIRED)
    private String selectionReason;

    @Schema(
            description = "Lifecycle status",
            example = "SUGGESTED",
            allowableValues = {"SUGGESTED", "ACCEPTED", "CONVERTED", "DISMISSED"},
            requiredMode = REQUIRED)
    private String status;

    @Schema(
            description = "Human-entered reason captured when the suggestion was dismissed",
            example = "Vendor contract under renegotiation",
            requiredMode = NOT_REQUIRED)
    private String dismissalReason;

    @Schema(
            description = "The DRAFT purchase order the suggestion was converted into",
            example = "01980003-0000-7000-8000-000000000005",
            requiredMode = NOT_REQUIRED)
    private UUID convertedPurchaseOrderId;

    @Schema(description = "Creation timestamp", example = "2026-07-23T09:30:00Z", requiredMode = REQUIRED)
    private Instant createdAt;

    @Schema(description = "Last update timestamp", example = "2026-07-23T09:30:00Z", requiredMode = REQUIRED)
    private Instant updatedAt;
}
