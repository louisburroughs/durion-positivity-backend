package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * Grand-total aging buckets across all rows of an aged AR or aged AP report.
 *
 * Bucket boundaries are computed from days-past-due (asOfDate minus the item's
 * due date). See the owning report DTO and the service javadoc for the exact
 * bucket edges. All amounts are non-negative outstanding balances.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Grand-total aging buckets across all rows of an aged AR/AP report")
public class AgingSummary {

    /**
     * Total outstanding not more than 30 days past due (includes not-yet-due).
     */
    @Schema(
            description = "Total outstanding 0-30 days past due (includes not-yet-due items)",
            example = "12500.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal current;

    /**
     * Total outstanding 31-60 days past due.
     */
    @Schema(description = "Total outstanding 31-60 days past due", example = "4200.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal days31To60;

    /**
     * Total outstanding 61-90 days past due.
     */
    @Schema(description = "Total outstanding 61-90 days past due", example = "1800.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal days61To90;

    /**
     * Total outstanding more than 90 days past due.
     */
    @Schema(description = "Total outstanding more than 90 days past due", example = "900.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal days90Plus;

    /**
     * Grand total outstanding across all buckets.
     */
    @Schema(description = "Grand total outstanding across all buckets", example = "19400.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalOutstanding;
}
