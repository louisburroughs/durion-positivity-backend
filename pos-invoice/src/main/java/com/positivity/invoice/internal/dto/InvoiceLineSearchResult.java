package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single invoice line item matched by the party-scoped line search
 * ({@code GET /v1/invoices/items/search?partyId=...}), flattened together with the identifying
 * fields of its owning invoice. Built for sibling services (warranty claims) that need to
 * correlate a claimed part/service back to the original sale line.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Invoice line item with owning-invoice context, matched by customer party")
public class InvoiceLineSearchResult {

    @Schema(
            description = "Identifier of the owning invoice",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    private UUID invoiceId;

    @Schema(description = "Human invoice number", example = "INV-2026-1001", requiredMode = NOT_REQUIRED)
    private String invoiceNumber;

    @Schema(
            description = "Identifier of the invoice line item",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    private UUID invoiceItemId;

    @Schema(description = "Line item description", example = "Front brake pads", requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(description = "Quantity sold", example = "2.0000", requiredMode = NOT_REQUIRED)
    private BigDecimal quantity;

    @Schema(description = "Unit price", example = "64.9900", requiredMode = NOT_REQUIRED)
    private BigDecimal unitPrice;

    @Schema(description = "Line total amount", example = "129.9800", requiredMode = NOT_REQUIRED)
    private BigDecimal amount;

    @Schema(description = "Originating workorder item identifier, when known", requiredMode = NOT_REQUIRED)
    private UUID workorderItemId;

    @Schema(description = "Line item type (e.g. PART, LABOR)", example = "PART", requiredMode = NOT_REQUIRED)
    private String itemType;

    @Schema(description = "Current status of the owning invoice", example = "POSTED", requiredMode = NOT_REQUIRED)
    private String invoiceStatus;

    @Schema(
            description = "Creation timestamp of the owning invoice",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant invoiceCreatedAt;
}
