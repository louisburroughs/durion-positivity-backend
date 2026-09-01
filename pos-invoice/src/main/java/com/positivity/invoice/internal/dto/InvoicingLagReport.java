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
 * Workorder-creation-to-invoice lag report (issue #1592, E4): today, exactly one row — the
 * whole-window aggregate. There is no {@code limit}/truncation contract on this endpoint: the
 * response is not a top-N list to cap, so a {@code limit} parameter would be a no-op and is
 * intentionally omitted.
 *
 * <p>Wave 3's {@code groupBy=month|week} (not implemented here) is additive against this shape
 * exactly like {@link RevenueByCustomerReport}: it would add more {@link InvoicingLagRow}
 * entries (one per period) plus a period field on the row, never change {@code rows} away from
 * a list.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Workorder-creation-to-invoice-creation lag report for one date window")
public class InvoicingLagReport {

    @Schema(description = "Window start date (inclusive)", example = "2026-06-01", requiredMode = REQUIRED)
    private LocalDate startDate;

    @Schema(description = "Window end date (inclusive)", example = "2026-06-30", requiredMode = REQUIRED)
    private LocalDate endDate;

    @Schema(description = "Aggregate rows — exactly one today, the whole-window aggregate", requiredMode = REQUIRED)
    private List<InvoicingLagRow> rows;
}
