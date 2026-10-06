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
 * due date): {@code notYetDue} holds everything due today or later, the four
 * late buckets hold overdue money, {@code overdue} is their sum and
 * {@code totalOutstanding} = {@code notYetDue} + {@code overdue} (CAP:550 S35,
 * #2524; the server sums, P7). All amounts are non-negative outstanding balances.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Grand-total aging buckets across all rows of an aged AR/AP report")
public class AgingSummary {

    /**
     * Total outstanding not yet due: due today or later (due today is not overdue).
     */
    @Schema(
            description = "Total outstanding not yet due: due today or later (due today is not overdue)",
            example = "9000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal notYetDue;

    /**
     * Total outstanding 1-30 days past due.
     */
    @Schema(description = "Total outstanding 1-30 days past due", example = "3500.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal days1To30;

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
     * Total overdue: the sum of the four late buckets (1-30, 31-60, 61-90, 90+).
     */
    @Schema(
            description = "Total overdue: the sum of days1To30, days31To60, days61To90 and days90Plus",
            example = "10400.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal overdue;

    /**
     * Grand total outstanding: notYetDue + overdue.
     */
    @Schema(description = "Grand total outstanding: notYetDue + overdue", example = "19400.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalOutstanding;
}
