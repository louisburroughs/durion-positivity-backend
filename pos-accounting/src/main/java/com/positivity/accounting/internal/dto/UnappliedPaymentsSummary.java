package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Totals over every payment matching the filter, not only the page returned (#2502, P7). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Totals over every payment matching the filter, whatever the page")
public class UnappliedPaymentsSummary {

    @Schema(description = "Number of matching payments", example = "3", requiredMode = REQUIRED)
    private long count;

    @Schema(description = "Sum of their unapplied amounts", example = "5265.00", requiredMode = REQUIRED)
    private BigDecimal totalUnappliedAmount;

    @Schema(
            description = "Currency of the total: the ledger currency (ISO 4217)",
            example = "USD",
            requiredMode = REQUIRED)
    private String currency;

    @Schema(description = "When the totals were computed", example = "2026-10-06T08:00:00Z", requiredMode = REQUIRED)
    private Instant asOf;
}
