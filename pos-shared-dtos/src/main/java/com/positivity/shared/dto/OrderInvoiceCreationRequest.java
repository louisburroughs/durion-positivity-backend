package com.positivity.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Service-to-service payload for creating an invoice from a sales order (counter-sale checkout,
 * order parity story C2). The order's totals are authoritative — pos-order has already priced
 * lines and computed tax via pos-tax — so pos-invoice records them rather than re-pricing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload for creating an invoice from a sales order at checkout.")
public class OrderInvoiceCreationRequest {

    @NotNull
    @Schema(
            description = "Sales order the invoice fronts; idempotency anchor (one invoice per order).",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID orderId;

    @Schema(
            description = "Workorder being settled through this order, when the order fronts a workorder "
                    + "settlement; the existing workorder invoice is returned instead of creating a duplicate.",
            example = "01960003-0000-7000-8000-000000000011",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID workorderId;

    @Schema(
            description = "Customer party; null for anonymous counter sales.",
            example = "01960003-0000-7000-8000-000000000012",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID customerId;

    @Schema(
            description = "Shop location of the sale.",
            example = "01960003-0000-7000-8000-000000000013",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID locationId;

    @NotNull
    @Schema(
            description = "Order subtotal (post-discount, pre-tax).",
            example = "120.00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal subtotal;

    @NotNull
    @Schema(
            description = "Order tax total (pos-tax authoritative).",
            example = "9.60",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal taxAmount;

    @NotNull
    @Schema(description = "Order grand total.", example = "129.60", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal totalAmount;

    @Valid
    @NotEmpty
    @Schema(description = "Order lines as sold.", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<OrderInvoiceLineItem> lines;

    @Schema(
            description = "When this order is a deposit / down-payment take (odoo-parity story E4), the source "
                    + "document type the deposit is held against; pos-invoice registers a deposit credit.",
            allowableValues = {"ESTIMATE", "WORKORDER", "ORDER"},
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String depositSourceType;

    @Schema(
            description = "Source document id the deposit is held against (required when depositSourceType is set).",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID depositSourceId;

    @Schema(
            description = "Deposit amount to register as a credit; when set, the order is a deposit take.",
            example = "200.00",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private BigDecimal depositAmount;
}
