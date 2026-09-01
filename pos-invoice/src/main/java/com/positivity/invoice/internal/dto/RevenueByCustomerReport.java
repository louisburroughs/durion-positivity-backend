package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Revenue-by-customer analytics report (issue #1589, E1): one window, dimensioned by customer,
 * ordered by {@code revenue} descending so the top N is simply the first {@code limit} rows —
 * there is no pagination to walk.
 *
 * <p>{@code truncated} is how a caller tells a capped result from a complete one without a
 * second call: it is {@code true} only when more customers had revenue in the window than
 * {@code limit} allowed through, i.e. rows beyond the cap exist but are not included.
 *
 * <p>Wave 3's {@code groupBy=month|week} (not implemented here) is additive against this shape:
 * it would add rows — one aggregate row per period per customer instead of one per customer —
 * plus a period field on {@link RevenueByCustomerRow}, never change {@code rows} from a list or
 * remove a field a Wave 2 client already reads.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Per-customer revenue report for one date window, ordered by revenue descending")
public class RevenueByCustomerReport {

    @Schema(description = "Window start date (inclusive)", example = "2026-06-01", requiredMode = REQUIRED)
    private LocalDate startDate;

    @Schema(description = "Window end date (inclusive)", example = "2026-06-30", requiredMode = REQUIRED)
    private LocalDate endDate;

    @Schema(description = "The effective row cap applied to this response", example = "20", requiredMode = REQUIRED)
    private int limit;

    @Schema(
            description =
                    "True when more customers had revenue in the window than `limit` allowed through — rows beyond the cap are not included",
            requiredMode = REQUIRED)
    private boolean truncated;

    @Schema(description = "Per-customer rows, revenue descending, bounded to `limit` entries", requiredMode = REQUIRED)
    private List<RevenueByCustomerRow> rows;
}
