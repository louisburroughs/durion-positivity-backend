package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * One per-customer row of the Aged Receivables report.
 *
 * Buckets the customer's open (unpaid) invoice balances by days past due as of
 * the report date. "Past due" is measured from the invoice's DUE date, falling
 * back to the invoice date when no due date is recorded (drafts, and replica rows
 * built from events predating due-date enrichment). Aged Payables applies the same
 * rule with the bill date as its fallback. An invoice due today or later is
 * {@code notYetDue}; the four late buckets hold overdue money and {@code overdue}
 * is their sum (CAP:550 S35, #2524). An invoice dated after the report date did
 * not yet exist and is not reported at all. All bucket amounts are non-negative;
 * {@code totalOutstanding} = {@code notYetDue} + {@code overdue}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Per-customer aged receivables row with bucketed open invoice balances. Age is measured "
                + "from the invoice due date, falling back to the invoice date when no due date is recorded; "
                + "invoices due today or later are notYetDue, the four late buckets are overdue money.")
public class AgedReceivablesRow {

    /**
     * Customer (party) identifier.
     */
    @Schema(
            description = "Customer (party) UUID",
            example = "d10217f9-3ec6-46b9-9c87-e7066c100c24",
            requiredMode = REQUIRED)
    @NonNull
    private UUID customerId;

    /**
     * Customer display name from accounting's customer replica (ADR-0044 R3); null when the replica
     * has not seen the party. Never an identifier (P8).
     */
    @Schema(
            description = "Customer display name from the customer replica; null when the party is not yet known",
            example = "Acme Fleet Services",
            requiredMode = NOT_REQUIRED)
    private String customerName;

    /**
     * Customer number from accounting's customer replica; null when the replica has not seen the
     * party. Never an identifier (P8).
     */
    @Schema(
            description = "Customer number from the customer replica; null when the party is not yet known",
            example = "C-10042",
            requiredMode = NOT_REQUIRED)
    private String customerReference;

    /**
     * Outstanding not yet due: due today or later (due today is not overdue).
     */
    @Schema(
            description = "Outstanding not yet due: due today or later (due today is not overdue)",
            example = "1000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal notYetDue;

    /**
     * Outstanding 1-30 days past due.
     */
    @Schema(description = "Outstanding 1-30 days past due", example = "250.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal days1To30;

    /**
     * Outstanding 31-60 days past due.
     */
    @Schema(description = "Outstanding 31-60 days past due", example = "500.00", requiredMode = REQUIRED)
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
            example = "750.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal overdue;

    /**
     * Total outstanding for the customer: notYetDue + overdue.
     */
    @Schema(
            description = "Total outstanding for the customer: notYetDue + overdue",
            example = "1750.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalOutstanding;
}
