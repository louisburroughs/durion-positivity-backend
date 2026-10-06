package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * One per-vendor row of the Aged Payables report.
 *
 * Buckets the vendor's open (unpaid) APPROVED vendor-bill balances by days past due
 * as of the report date (AW11; CAP:550 S35, #2524). "Past due" is measured from the
 * bill's DUE date, falling back to the bill date when no due date is recorded. Aged
 * Receivables applies the same rule with the invoice date as its fallback. A bill due
 * today or later is {@code notYetDue}; the four late buckets hold overdue money and
 * {@code overdue} is their sum. A bill dated after the report date did not yet exist
 * and is not reported at all. Open balance per bill is the bill total minus applied
 * {@code APPaymentAllocation} amounts. All bucket amounts are non-negative;
 * {@code totalOutstanding} = {@code notYetDue} + {@code overdue}.
 *
 * <p>Bills not yet approved ({@code PENDING_RECEIPT_MATCH}, {@code MATCH_EXCEPTION})
 * never reach a bucket: their open amount is reported beside the buckets as
 * {@code unapproved}, with {@code unapprovedBillCount} and
 * {@code totalIncludingUnapproved}. A vendor with only unapproved bills has a row
 * with zero buckets.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Per-vendor aged payables row with bucketed open vendor-bill balances. Age is measured "
                + "from the bill due date, falling back to the bill date when no due date is recorded; only "
                + "APPROVED bills are aged, unapproved bills are reported separately and unaged.")
public class AgedPayablesRow {

    /**
     * Vendor identifier.
     */
    @Schema(description = "Vendor UUID", example = "d10217f9-3ec6-46b9-9c87-e7066c100c24", requiredMode = REQUIRED)
    @NonNull
    private UUID vendorId;

    /**
     * Vendor display name (may be null when the directory lookup is unavailable).
     */
    @Schema(description = "Vendor display name", example = "Global Parts Supply", requiredMode = NOT_REQUIRED)
    private String vendorName;

    /**
     * Approved bills outstanding not yet due: due today or later (due today is not overdue).
     */
    @Schema(
            description = "Approved bills outstanding not yet due: due today or later (due today is not overdue)",
            example = "2500.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal notYetDue;

    /**
     * Approved bills outstanding 1-30 days past due.
     */
    @Schema(description = "Approved bills outstanding 1-30 days past due", example = "700.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal days1To30;

    /**
     * Outstanding 31-60 days past due.
     */
    @Schema(description = "Outstanding 31-60 days past due", example = "750.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal days31To60;

    /**
     * Outstanding 61-90 days past due.
     */
    @Schema(description = "Outstanding 61-90 days past due", example = "0.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal days61To90;

    /**
     * Outstanding more than 90 days past due.
     */
    @Schema(description = "Outstanding more than 90 days past due", example = "0.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal days90Plus;

    /**
     * Overdue: the sum of the four late buckets.
     */
    @Schema(
            description = "Overdue: the sum of days1To30, days31To60, days61To90 and days90Plus",
            example = "1450.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal overdue;

    /**
     * Total outstanding on approved bills: notYetDue + overdue.
     */
    @Schema(
            description = "Total outstanding on approved bills: notYetDue + overdue",
            example = "3950.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalOutstanding;

    /**
     * Open amount on bills not yet approved (PENDING_RECEIPT_MATCH, MATCH_EXCEPTION); never aged.
     */
    @Schema(
            description = "Open amount on bills not yet approved (PENDING_RECEIPT_MATCH, MATCH_EXCEPTION); never aged",
            example = "300.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal unapproved;

    /**
     * Number of open bills not yet approved.
     */
    @Schema(description = "Number of open bills not yet approved", example = "2", requiredMode = REQUIRED)
    private int unapprovedBillCount;

    /**
     * totalOutstanding + unapproved.
     */
    @Schema(description = "totalOutstanding + unapproved", example = "4250.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalIncludingUnapproved;
}
