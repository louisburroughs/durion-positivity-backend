package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * Aged Receivables (aged AR) report response.
 *
 * Buckets open (unpaid) customer invoice balances by days past due as of the
 * report date (0-30 / 31-60 / 61-90 / 90+). Rows are per customer, ordered by
 * customer name; {@code totals} carries the grand-total buckets across rows.
 *
 * Rows and totals are all-zero when no open receivables exist as of the date.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Aged Receivables report with per-customer bucketed open invoice balances")
public class AgedReceivablesReport {

    /**
     * Report as-of date (inclusive) used to compute days past due.
     */
    @Schema(
            description = "Date the receivables aging is reported as of (inclusive)",
            example = "2026-06-30",
            requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate asOfDate;

    /**
     * Timestamp when the report was generated.
     */
    @Schema(
            description = "Timestamp when the report was generated (ISO 8601)",
            example = "2026-06-30T08:00:00Z",
            requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant generatedAt;

    /**
     * Per-customer aging rows ordered by customer name. Empty when no open
     * receivables exist as of the requested date.
     */
    @Schema(
            description = "Per-customer aging rows ordered by customer name; empty when no open receivables exist",
            requiredMode = REQUIRED)
    @NonNull
    private List<AgedReceivablesRow> rows;

    /**
     * Grand-total aging buckets across all rows.
     */
    @Schema(description = "Grand-total aging buckets across all rows", requiredMode = REQUIRED)
    @NonNull
    private AgingSummary totals;
}
