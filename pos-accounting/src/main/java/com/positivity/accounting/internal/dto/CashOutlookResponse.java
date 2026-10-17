package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The cash outlook (CAP:550 S19, #2515; SPEC-accounting-workspace §4.2, §5.1 item 5, §7.1 "Cash outlook"; AW11): a
 * projection of the bank plus what waits to be deposited over the next days, from committed items only. Nothing is
 * posted or stored. Every amount is in {@code currencyCode}, the functional currency (ADR-0067 R-1).
 */
@Schema(description = "A projection of cash over the coming days from approved bills and open invoices")
public record CashOutlookResponse(
        @Schema(description = "When the outlook was computed", example = "2026-10-17T15:00:00Z")
        Instant asOf,

        @Schema(description = "Always true: this is a projection, never a posted figure", example = "true")
        boolean projection,

        @Schema(description = "ISO 4217 code of every amount: the functional currency", example = "USD")
        String currencyCode,

        @Schema(description = "The first day, today in the tenant's accounting time zone", example = "2026-10-17")
        LocalDate startDate,

        @Schema(description = "Days projected after the first", example = "30")
        int days,

        @Schema(
                description = "Where the projection starts: in the bank plus waiting to be deposited, never drawers or"
                        + " the float",
                example = "13640.00")
        BigDecimal startingBalance,

        @Schema(description = "The projected balance at the end of each day, days plus one points from today")
        List<Point> points,

        @Schema(description = "The lowest projected balance and its first day")
        Point lowPoint,

        @Schema(description = "Money in over the period: open invoices on their due dates")
        BigDecimal expectedIn,

        @Schema(description = "Money out over the period: approved bills on their due dates")
        BigDecimal expectedOut,

        @Schema(
                description = "Open bills not yet approved, shown apart and not in the projection; a pending bill's"
                        + " approval would put it in expectedOut")
        Figure possibleOut,

        @Schema(description = "Open invoices already past due: never assumed collected, not in the projection")
        Figure overdueIn,

        @Schema(
                description = "Vendor credit notes (negative bills): the credit owed to the shop as a positive figure,"
                        + " not netted into the projection")
        Figure vendorCredits,

        @Schema(description = "Approved bills whose due date is an estimate, and their open amount")
        Figure estimatedDueDates,

        @Schema(description = "Open approved bills split by due state, for the bills lane")
        BillsLane billsLane,

        @Schema(description = "The tenant's cash safety cushion; null while unset") @Nullable
        BigDecimal safetyCushion,

        @Schema(
                description = "True when the low point is strictly below the cushion; false while no cushion is set",
                example = "false")
        boolean belowCushion) {

    /** One day of the projection. */
    @Schema(name = "CashOutlookPoint", description = "The projected balance at the end of one day")
    public record Point(
            @Schema(description = "The day", example = "2026-10-17")
            LocalDate date,

            @Schema(description = "The projected balance at the end of the day", example = "13640.00")
            BigDecimal balance,

            @Schema(description = "Money in that day", example = "0.00")
            BigDecimal moneyIn,

            @Schema(description = "Money out that day, estimated due dates included", example = "500.00")
            BigDecimal moneyOut,

            @Schema(
                    description = "The part of that day's money out whose due date is an estimate (estimated true)",
                    example = "500.00")
            BigDecimal estimatedMoneyOut) {}

    /** An amount and how many items make it up. */
    @Schema(name = "CashOutlookFigure", description = "An amount and the number of items behind it")
    public record Figure(
            @Schema(description = "The amount", example = "300.00")
            BigDecimal amount,

            @Schema(description = "How many items", example = "2")
            int count) {}

    /** The open approved bills by due state; the server adds them up (P7). */
    @Schema(name = "CashOutlookBillsLane", description = "Open approved bills by due state")
    public record BillsLane(
            @Schema(description = "Past their real due date")
            Figure overdue,

            @Schema(description = "Due from today through the seventh day after, by their real due date")
            Figure dueIn7Days,

            @Schema(description = "Due later, by their real due date")
            Figure dueLater,

            @Schema(description = "Without a real due date: their due date is estimated")
            Figure dueDateEstimated,

            @Schema(description = "The four added up", example = "1150.00")
            BigDecimal total) {}
}
