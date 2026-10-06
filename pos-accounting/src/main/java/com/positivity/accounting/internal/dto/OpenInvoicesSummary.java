package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Totals over every open invoice of the customer, not only the page returned (#2502, P7). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Totals over every open invoice of the customer, whatever the page")
public class OpenInvoicesSummary {

    @Schema(description = "Number of open invoices", example = "2", requiredMode = REQUIRED)
    private long count;

    @Schema(description = "Sum of their balances due", example = "4895.00", requiredMode = REQUIRED)
    private BigDecimal totalBalanceDue;

    @Schema(description = "Number of overdue open invoices", example = "1", requiredMode = REQUIRED)
    private long overdueCount;

    @Schema(description = "Sum of the overdue balances due", example = "280.00", requiredMode = REQUIRED)
    private BigDecimal overdueBalanceDue;

    @Schema(
            description = "Currency of the totals: the ledger currency (ISO 4217)",
            example = "USD",
            requiredMode = REQUIRED)
    private String currency;

    @Schema(description = "When the totals were computed", example = "2026-10-06T08:00:00Z", requiredMode = REQUIRED)
    private Instant asOf;
}
