package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * One payment-lag cohort row (Wave 2 E3, Issue #1591).
 *
 * <p>Cohort assignment and the {@code amount} counting rule for unpaid/partial invoices are
 * documented on {@link com.positivity.accounting.internal.service.AccountingAnalyticsService
 * #getPaymentLagCohorts}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "One payment-lag cohort: invoice count and total invoice amount for invoices whose"
                + " full-payment lag (or non-payment) falls in this band")
public class PaymentLagCohortRow {

    @Schema(
            description = "Cohort label. <=30 / 31-60 / 61-90: whole days from the invoice's issue-date anchor to"
                    + " the PaymentApplication at which invoiceBalanceAfter first reached zero (boundaries"
                    + " inclusive at the upper edge, e.g. 31-60 includes exactly 60). unpaid: no application"
                    + " ever brought the balance to zero in the observed data, the lag exceeded 90 days, or"
                    + " the invoice is only partially applied.",
            example = "<=30",
            requiredMode = REQUIRED)
    @NonNull
    private String cohort;

    @Schema(description = "Count of invoices in this cohort", example = "42", requiredMode = REQUIRED)
    @NonNull
    private Integer invoiceCount;

    @Schema(
            description = "Sum of invoice totals in this cohort — the invoice's full amount, not its remaining open"
                    + " balance",
            example = "84250.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal amount;
}
