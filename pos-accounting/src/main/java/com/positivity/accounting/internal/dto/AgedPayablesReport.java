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
 * Aged Payables (aged AP) report response.
 *
 * Mirrors the aged AR structure for the payables side: buckets open (unpaid)
 * vendor-bill balances by days past due as of the report date
 * (0-30 / 31-60 / 61-90 / 90+). Rows are per vendor, ordered by vendor name;
 * {@code totals} carries the grand-total buckets across rows.
 *
 * Rows and totals are all-zero when no open payables exist as of the date.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Aged Payables report with per-vendor bucketed open vendor-bill balances")
public class AgedPayablesReport {

    /**
     * Report as-of date (inclusive) used to compute days past due.
     */
    @Schema(
            description = "Date the payables aging is reported as of (inclusive)",
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
     * Per-vendor aging rows ordered by vendor name. Empty when no open payables
     * exist as of the requested date.
     */
    @Schema(
            description = "Per-vendor aging rows ordered by vendor name; empty when no open payables exist",
            requiredMode = REQUIRED)
    @NonNull
    private List<AgedPayablesRow> rows;

    /**
     * Grand-total aging buckets across all rows.
     */
    @Schema(description = "Grand-total aging buckets across all rows", requiredMode = REQUIRED)
    @NonNull
    private AgingSummary totals;
}
