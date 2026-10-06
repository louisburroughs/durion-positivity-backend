package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * Aged Payables (aged AP) report response.
 *
 * Mirrors the aged AR structure for the payables side: buckets open (unpaid)
 * APPROVED vendor-bill balances by days past due as of the report date
 * (not yet due / 1-30 / 31-60 / 61-90 / 90+; AW11, CAP:550 S35). Rows are per
 * vendor, ordered by vendor name; {@code totals} carries the grand-total buckets
 * across rows. Bills not yet approved are never aged: their open amount is
 * reported beside the totals as {@code unapproved}, {@code unapprovedBillCount}
 * and {@code totalIncludingUnapproved}.
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
     * Grand-total aging buckets across all rows (approved bills only).
     */
    @Schema(description = "Grand-total aging buckets across all rows (approved bills only)", requiredMode = REQUIRED)
    @NonNull
    private AgingSummary totals;

    /**
     * Open amount on bills not yet approved across all vendors; never aged.
     */
    @Schema(
            description = "Open amount on bills not yet approved (PENDING_RECEIPT_MATCH, MATCH_EXCEPTION) across all"
                    + " vendors; never aged",
            example = "300.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal unapproved;

    /**
     * Number of open bills not yet approved across all vendors.
     */
    @Schema(
            description = "Number of open bills not yet approved across all vendors",
            example = "2",
            requiredMode = REQUIRED)
    private int unapprovedBillCount;

    /**
     * totals.totalOutstanding + unapproved.
     */
    @Schema(description = "totals.totalOutstanding + unapproved", example = "800.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalIncludingUnapproved;
}
