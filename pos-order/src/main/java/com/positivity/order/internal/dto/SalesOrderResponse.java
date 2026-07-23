package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response representing a sales order and its lines")
public class SalesOrderResponse {

    @Schema(
            description = "Unique identifier of the order",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private String orderId;

    @Schema(
            description = "Human-facing order number assigned at creation",
            example = "SO-1A2B3C4D-2607-000042",
            requiredMode = NOT_REQUIRED)
    private String orderNumber;

    @Schema(
            description = "Shop location the order belongs to",
            example = "01960003-0000-7000-8000-000000000090",
            requiredMode = NOT_REQUIRED)
    private String locationId;

    @Schema(
            description = "Parking label for resumable drafts",
            example = "blue F-150 waiting on customer",
            requiredMode = NOT_REQUIRED)
    private String label;

    @Schema(
            description = "CRM validation state of the customer/vehicle references (VALIDATED or PENDING)",
            example = "VALIDATED",
            requiredMode = NOT_REQUIRED)
    private String customerValidationStatus;

    @Schema(
            description = "Identifier of the customer associated with the order",
            example = "01960003-0000-7000-8000-000000000070",
            requiredMode = NOT_REQUIRED)
    private String customerId;

    @Schema(
            description = "Identifier of the vehicle associated with the order",
            example = "01960003-0000-7000-8000-000000000080",
            requiredMode = NOT_REQUIRED)
    private String vehicleId;

    @Schema(
            description = "Identifier of the clerk who owns the order",
            example = "01960003-0000-7000-8000-000000000050",
            requiredMode = NOT_REQUIRED)
    private String clerkId;

    @Schema(
            description = "Identifier of the terminal where the order was created",
            example = "01960003-0000-7000-8000-000000000060",
            requiredMode = NOT_REQUIRED)
    private String terminalId;

    @Schema(description = "Current status of the order", example = "OPEN", requiredMode = REQUIRED)
    @NotNull
    private String status;

    @Schema(
            description = "Σ line subtotals: post-line-discount, pre-order-discount, pre-tax",
            example = "59.97",
            requiredMode = NOT_REQUIRED)
    private BigDecimal subtotal;

    @Schema(description = "Σ line discounts + allocated order discount", example = "6.00", requiredMode = NOT_REQUIRED)
    private BigDecimal discountTotal;

    @Schema(description = "Total tax (pos-tax authoritative)", example = "4.32", requiredMode = NOT_REQUIRED)
    private BigDecimal taxTotal;

    @Schema(description = "subtotal − order discount + taxTotal", example = "58.29", requiredMode = NOT_REQUIRED)
    private BigDecimal grandTotal;

    @Schema(
            description = "True when mutations invalidated taxTotal; cleared by quote/checkout tax recompute",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private boolean taxStale;

    @Schema(description = "Order discount flavor (PERCENT or AMOUNT)", requiredMode = NOT_REQUIRED)
    private String orderDiscountType;

    @Schema(description = "Order discount value per type", requiredMode = NOT_REQUIRED)
    private BigDecimal orderDiscountValue;

    @Schema(description = "Order discount reason code", requiredMode = NOT_REQUIRED)
    private String orderDiscountReasonCode;

    @Schema(description = "Order-level note", requiredMode = NOT_REQUIRED)
    private String generalNote;

    @Schema(description = "Quote validity horizon (QUOTED orders)", requiredMode = NOT_REQUIRED)
    private Instant quoteExpiresAt;

    @Schema(description = "Invoice fronting this order, set at checkout", requiredMode = NOT_REQUIRED)
    private String invoiceId;

    @Schema(description = "Human-facing invoice number", requiredMode = NOT_REQUIRED)
    private String invoiceNumber;

    @Schema(description = "Net settled amount (checkout onward)", requiredMode = NOT_REQUIRED)
    private BigDecimal amountPaid;

    @Schema(description = "Outstanding balance (checkout onward)", requiredMode = NOT_REQUIRED)
    private BigDecimal balanceDue;

    @Schema(
            description = "Timestamp when the order was created (ISO 8601)",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the order was last updated (ISO 8601)",
            example = "2026-01-15T09:45:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;

    @Schema(
            description = "Identifier of the user who created the order",
            example = "01960003-0000-7000-8000-000000000050",
            requiredMode = NOT_REQUIRED)
    private String createdBy;

    @Schema(
            description = "Identifier of the user who last updated the order",
            example = "01960003-0000-7000-8000-000000000051",
            requiredMode = NOT_REQUIRED)
    private String updatedBy;

    @Schema(description = "Lines belonging to the order", requiredMode = NOT_REQUIRED)
    @Valid
    @Builder.Default
    private List<SalesOrderLineResponse> lines = List.of();
}
