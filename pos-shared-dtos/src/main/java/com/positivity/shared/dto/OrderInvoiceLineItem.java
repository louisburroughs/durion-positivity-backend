package com.positivity.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One sold line on an order-fronted invoice (order parity story C2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Sales order line for invoice creation.")
public class OrderInvoiceLineItem {

    @Schema(
            description = "Sales order line identifier, carried for settlement reconciliation.",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID orderLineId;

    @NotNull
    @Schema(
            description = "Line description as sold.",
            example = "Brake pad set",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String description;

    @NotNull
    @Schema(description = "Quantity sold.", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal quantity;

    @NotNull
    @Schema(description = "Applied unit price.", example = "45.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal unitPrice;

    @NotNull
    @Schema(
            description = "Extended line amount (post-line-discount, pre-tax).",
            example = "90.00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;

    @Schema(description = "Per-line tax amount.", example = "7.20", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private BigDecimal taxAmount;

    @Schema(
            description = "Line item type (e.g. PART, LABOR, FEE).",
            example = "PART",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String type;
}
