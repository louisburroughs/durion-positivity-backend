package com.positivity.inventory.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Full representation of a purchase order, including header totals and line items")
public class PurchaseOrderResponse {

    @Schema(
            description = "Unique identifier of the purchase order",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID purchaseOrderId;

    @Schema(
            description = "Identifier of the vendor the purchase order is placed with",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID vendorId;

    @Schema(
            description = "Human-readable purchase order number",
            example = "PO-2026-00042",
            requiredMode = REQUIRED)
    @NotNull
    private String poNumber;

    @Schema(
            description = "Current lifecycle status of the purchase order",
            example = "APPROVED",
            requiredMode = REQUIRED)
    @NotNull
    private PurchaseOrderStatus status;

    @Schema(
            description = "Revision/version number of the purchase order, incremented on each revision",
            example = "1",
            requiredMode = REQUIRED)
    @NotNull
    private Integer versionNumber;

    @Schema(
            description = "ISO 4217 currency code the purchase order is denominated in",
            example = "USD",
            requiredMode = REQUIRED)
    @NotNull
    private String currency;

    @Schema(
            description = "Sum of line totals before tax in the smallest currency unit (e.g. cents)",
            example = "17988",
            requiredMode = NOT_REQUIRED)
    private Long subtotalMinor;

    @Schema(
            description = "Total tax amount in the smallest currency unit (e.g. cents)",
            example = "1439",
            requiredMode = NOT_REQUIRED)
    private Long taxMinor;

    @Schema(
            description = "Grand total including tax in the smallest currency unit (e.g. cents)",
            example = "19427",
            requiredMode = NOT_REQUIRED)
    private Long grandTotalMinor;

    @Schema(
            description = "Remaining unpaid/unreceived balance in the smallest currency unit (e.g. cents)",
            example = "19427",
            requiredMode = NOT_REQUIRED)
    private Long openBalanceMinor;

    @Schema(
            description = "Identifier of the location the goods are shipped to",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID shipToLocationId;

    @Schema(
            description = "Identifier of the payment terms applied to this purchase order",
            example = "NET30",
            requiredMode = NOT_REQUIRED)
    private String paymentTermsId;

    @Schema(
            description = "Date the purchase order was issued",
            example = "2026-01-15",
            requiredMode = NOT_REQUIRED)
    private LocalDate poDate;

    @Schema(
            description = "Date the ordered goods are expected to be delivered",
            example = "2026-01-29",
            requiredMode = NOT_REQUIRED)
    private LocalDate expectedDeliveryDate;

    @Schema(
            description = "Identifier or name of the person who requested the purchase order",
            example = "user-jdoe",
            requiredMode = NOT_REQUIRED)
    private String requestedBy;

    @Schema(
            description = "Free-text comment providing additional context for the purchase order",
            example = "Urgent restock for spring promotion",
            requiredMode = NOT_REQUIRED)
    private String comment;

    @Schema(
            description = "Identifier of the person who approved the purchase order",
            example = "user-asmith",
            requiredMode = NOT_REQUIRED)
    private String approverId;

    @Schema(
            description = "Timestamp at which the purchase order was approved",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant approvalTimestamp;

    @Schema(
            description = "Notes recorded by the approver explaining the approval decision",
            example = "Approved within budget for Q1 restock",
            requiredMode = NOT_REQUIRED)
    private String approvalNotes;

    @Schema(
            description = "Reference to the financial encumbrance reserved for this purchase order",
            example = "ENC-2026-00042",
            requiredMode = NOT_REQUIRED)
    private String encumbranceRef;

    @Schema(
            description = "Identifier of the user who created the purchase order",
            example = "user-jdoe",
            requiredMode = NOT_REQUIRED)
    private String createdBy;

    @Schema(
            description = "Identifier of the user who last updated the purchase order",
            example = "user-asmith",
            requiredMode = NOT_REQUIRED)
    private String updatedBy;

    @Schema(
            description = "Timestamp at which the purchase order was created",
            example = "2026-01-15T09:00:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(
            description = "Timestamp at which the purchase order was last updated",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant updatedAt;

    @Schema(
            description = "Line items belonging to this purchase order",
            requiredMode = NOT_REQUIRED)
    private List<PurchaseOrderLineResponse> lines;
}
