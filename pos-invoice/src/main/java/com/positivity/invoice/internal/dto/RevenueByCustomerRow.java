package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One customer's revenue over the report window (issue #1589, E1).
 *
 * <p>{@code avgInvoiceValue} is {@code revenue / invoiceCount}, computed here rather than left
 * to the caller so every consumer of this report agrees on the figure. {@code lastInvoiceDate}
 * is the most recent {@code Invoice.createdAt} among the revenue-recognized invoices that
 * contributed to this row's {@code revenue} — it is load-bearing, not decoration: it is what
 * lets a caller tell a customer who has gone quiet from one who is merely low-volume.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One customer's aggregated revenue over the report window")
public class RevenueByCustomerRow {

    @Schema(
            description =
                    "Customer party identifier (canonical lowercase UUID string, matching the invoice's own party id column)",
            example = "018f0000-0000-7000-8000-0000000000aa",
            requiredMode = REQUIRED)
    private String customerId;

    @Schema(
            description =
                    "Resolved customer display name, from the local customer-party replica; null if the replica has not caught up to this party yet",
            example = "Acme Towing LLC",
            requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(
            description = "Total revenue-recognized invoice value for this customer in the window",
            example = "4899.50",
            requiredMode = REQUIRED)
    private BigDecimal revenue;

    @Schema(
            description = "Number of revenue-recognized invoices contributing to revenue",
            example = "12",
            requiredMode = REQUIRED)
    private long invoiceCount;

    @Schema(
            description = "revenue / invoiceCount, computed server-side (4 decimal places, HALF_UP)",
            example = "408.2917",
            requiredMode = REQUIRED)
    private BigDecimal avgInvoiceValue;

    @Schema(
            description = "Creation timestamp of this customer's most recent contributing invoice in the window",
            example = "2026-06-30T18:12:00Z",
            requiredMode = REQUIRED)
    private Instant lastInvoiceDate;
}
