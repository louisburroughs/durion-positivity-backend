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
 * Response for from-order invoice creation (order parity story C2). {@code existing} is true when
 * the call replayed an already-created invoice — the orderId idempotency replay or the
 * workorder-dedupe path returning the workorder's invoice for tender.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response returned after creating (or replaying) an invoice from a sales order.")
public class OrderInvoiceResponse {

    @NotNull
    @Schema(description = "Invoice identifier.", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID invoiceId;

    @Schema(description = "Human-facing invoice number.", example = "INV-1770000000000-01960003")
    private String invoiceNumber;

    @NotNull
    @Schema(description = "Invoice status.", example = "DRAFT", requiredMode = Schema.RequiredMode.REQUIRED)
    private String status;

    @Schema(description = "Invoice subtotal.", example = "120.00")
    private BigDecimal subtotal;

    @Schema(description = "Invoice tax amount.", example = "9.60")
    private BigDecimal taxAmount;

    @Schema(description = "Invoice total.", example = "129.60")
    private BigDecimal totalAmount;

    @Schema(
            description = "Deposit / down-payment credit applied to this invoice at creation "
                    + "(odoo-parity story E4); zero when no deposit was held against the source.",
            example = "50.00")
    private BigDecimal depositApplied;

    @Schema(
            description = "True when an existing invoice was returned (orderId replay or workorder dedupe).",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean existing;
}
