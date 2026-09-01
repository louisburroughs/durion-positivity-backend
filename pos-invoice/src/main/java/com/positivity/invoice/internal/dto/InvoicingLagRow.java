package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One aggregate row of the workorder-creation-to-invoice lag report (issue #1592, E4).
 *
 * <p>{@code count} rides alongside {@code avgDaysWoCreationToInvoice} so a thin window (a
 * handful of invoices) reads as thin rather than as a swing in the trend — an average of 1.5
 * days from 2 invoices and one from 200 are not the same signal. {@code
 * avgDaysWoCreationToInvoice} is {@code null} when {@code count} is 0: an average of nothing is
 * undefined, not zero, and a caller must not be able to mistake "no data" for "same-day".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One aggregate row of workorder-creation-to-invoice-creation lag")
public class InvoicingLagRow {

    @Schema(
            description =
                    "Average days from workorder creation to invoice creation across `count` invoices; null when count is 0 (undefined, not zero)",
            example = "3.42",
            requiredMode = NOT_REQUIRED)
    private Double avgDaysWoCreationToInvoice;

    @Schema(
            description =
                    "Number of invoices this average is computed over. Excludes invoices with no linked workorder and workorders whose creation timestamp has not replicated yet (#1592) — never counted as zero lag.",
            example = "47",
            requiredMode = REQUIRED)
    private long count;
}
