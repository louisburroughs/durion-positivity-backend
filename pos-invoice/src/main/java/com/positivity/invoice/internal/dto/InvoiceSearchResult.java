package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.invoice.internal.enums.InvoiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight invoice search row for the billing invoice finder.
 *
 * <p>Returned by {@code GET /v1/invoices/search?q=...} which matches the query against the
 * invoice number, the customer name, or the workorder number. Customer name and workorder
 * number are resolved from sibling services (CRM, workorder) since the invoice row stores
 * only the party id and the workorder id.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Lightweight invoice search result enriched with customer name and workorder number")
public class InvoiceSearchResult {

    @Schema(
            description = "Invoice identifier",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    private UUID invoiceId;

    @Schema(description = "Human invoice number", example = "INV-2026-1001", requiredMode = NOT_REQUIRED)
    private String invoiceNumber;

    @Schema(description = "Resolved customer display name", example = "Acme Towing LLC", requiredMode = NOT_REQUIRED)
    private String customerName;

    @Schema(description = "Linked workorder identifier", requiredMode = NOT_REQUIRED)
    private UUID workorderId;

    @Schema(description = "Resolved human workorder number", example = "WO-2026-1001", requiredMode = NOT_REQUIRED)
    private String workorderNumber;

    @Schema(description = "Current invoice status", example = "DRAFT", requiredMode = REQUIRED)
    private InvoiceStatus status;

    @Schema(description = "Invoice grand total", example = "249.95", requiredMode = NOT_REQUIRED)
    private BigDecimal total;

    @Schema(description = "Creation timestamp", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    private Instant createdAt;
}
