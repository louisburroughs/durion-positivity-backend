package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-vendor spend analytics report for one date window (Wave 2 E8, issue #1596), ordered by
 * {@code paidAmount} descending so the top N by spend is simply the first {@code limit} rows —
 * there is no pagination to walk.
 *
 * <p>{@code truncated} is how a caller tells a capped result from a complete one without a
 * second call: it is {@code true} only when more vendors had activity in the window than {@code
 * limit} allowed through.
 *
 * <p>See {@link VendorSpendRow} for the important distinction between the payment-side and
 * bill-side figures carried on each row.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(
        description = "Per-vendor spend report for one date window, ordered by paidAmount descending. paidAmount"
                + " (settled A/P cash) and billsIssuedInWindow/avgIssuedBillAmount (bill records) are different"
                + " populations — see VendorSpendRow field descriptions.")
public class VendorSpendReport {

    @Schema(description = "Window start date (inclusive)", example = "2026-06-01", requiredMode = REQUIRED)
    private LocalDate startDate;

    @Schema(description = "Window end date (inclusive)", example = "2026-06-30", requiredMode = REQUIRED)
    private LocalDate endDate;

    @Schema(
            description = "Timestamp when the report was generated (ISO 8601)",
            example = "2026-06-30T08:00:00Z",
            requiredMode = REQUIRED)
    private Instant generatedAt;

    @Schema(description = "The effective row cap applied to this response", example = "20", requiredMode = REQUIRED)
    private int limit;

    @Schema(
            description = "True when more vendors had settled payments or bills in the window than `limit`"
                    + " allowed through — rows beyond the cap are not included",
            requiredMode = REQUIRED)
    private boolean truncated;

    @Schema(description = "Per-vendor rows, paidAmount descending, bounded to `limit` entries", requiredMode = REQUIRED)
    private List<VendorSpendRow> rows;
}
