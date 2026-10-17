package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.positivity.accounting.internal.enums.CashAttentionCode;
import com.positivity.accounting.internal.enums.CashAttentionReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Your cash (CAP:550 S19, #2515; SPEC-accounting-workspace §4.1, §7.1 "Cash position read"; AW9): what the shop holds
 * and where it sits, from the ledger, and what needs attention. Computed on read from the ledger and replicas, never
 * stored, and never a sum the client makes (P7). Every amount is in {@code currencyCode}, the functional currency
 * (ADR-0067 R-1).
 */
@Schema(description = "Your cash: in the bank, waiting to be deposited and kept in drawers, with what needs attention")
public record CashPositionResponse(
        @Schema(description = "When the ledger figures were read", example = "2026-10-17T15:00:00Z")
        Instant asOf,

        @Schema(
                description = "When the newest replica row behind an event-fed figure (the unpaid walk-in sales) was"
                        + " written; null when there is none")
        @Nullable
        Instant eventFedAsOf,

        @Schema(
                description = "Whether an event-fed figure trails the register. Replica lag cannot be measured yet (the"
                        + " ingestion record has no emission time), so this is always false",
                example = "false")
        boolean lagging,

        @Schema(description = "ISO 4217 code of every amount: the functional currency", example = "USD")
        String currencyCode,

        @Schema(
                description = "Your cash: in the bank plus waiting to be deposited plus kept in drawers; settlement"
                        + " suspense is never part of it and the bank statement's view is never added",
                example = "14197.00")
        BigDecimal total,

        @Schema(description = "Money in the bank: the active bank accounts' books balances")
        Component inTheBank,

        @Schema(
                description = "Money waiting to be deposited: undeposited funds (1090) and register cash clearing"
                        + " (1095), shown and netted")
        Component waitingToBeDeposited,

        @Schema(description = "Cash kept in drawers: the cash-on-hand accounts (1080)")
        Component keptInDrawers,

        @Schema(
                description = "Active bank accounts left out because they are in a currency other than the"
                        + " functional one",
                example = "0")
        int excludedForeignCurrencyAccounts,

        @Schema(description = "What needs attention, in a fixed order; empty when nothing does")
        List<NeedsAttention> needsAttention,

        @Schema(description = "The CASH walk-in house account's balance; null when it is zero and the account is known")
        @Nullable
        UnpaidWalkInSales unpaidWalkInSales) {

    /** One component of the total with the accounts behind it. */
    @Schema(name = "CashPositionComponent", description = "One component of your cash and the accounts behind it")
    public record Component(
            @Schema(description = "The accounts' balances added up", example = "12400.00")
            BigDecimal total,

            @Schema(description = "One row per account, by account code")
            List<AccountRow> accounts) {}

    /**
     * One account of a component. The statement fields exist only on bank rows, only for a caller who also holds
     * {@code accounting:reconciliation:view}; otherwise they are absent.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "CashPositionAccount", description = "One account of a cash component")
    public record AccountRow(
            @Schema(description = "The account, for links") UUID glAccountId,

            @Schema(description = "Account code", example = "1000")
            String accountCode,

            @Schema(description = "Account name", example = "Operating Bank")
            String accountName,

            @Schema(
                    description = "Books balance: debits minus credits over posted and reversed entries",
                    example = "12400.00")
            BigDecimal balance,

            @Schema(
                    description = "Closing balance of the latest committed bank statement; shown beside the books"
                            + " balance, never added to the total")
            @Nullable
            BigDecimal statementClosingBalance,

            @Schema(description = "End date of that statement") @Nullable
            LocalDate statementEndDate,

            @Schema(description = "The date the account is reconciled through; null when never reconciled") @Nullable
            LocalDate reconciledThrough,

            @Schema(description = "Open deposits in transit: how many and their total") @Nullable
            TimingItems depositsInTransit,

            @Schema(description = "Open outstanding checks: how many and their total, a negative figure") @Nullable
            TimingItems outstandingChecks,

            @Schema(description = "Bank lines still to be matched or reviewed", example = "2") @Nullable
            Long unmatchedBankLineCount) {}

    /** Open outstanding items of one kind. */
    @Schema(name = "CashPositionTimingItems", description = "Open outstanding items of one kind")
    public record TimingItems(
            @Schema(description = "How many are open", example = "1")
            long count,

            @Schema(description = "Their total, signed as the ledger line (debit minus credit)", example = "450.00")
            BigDecimal amount) {}

    /** One thing that needs attention. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "CashNeedsAttention", description = "One thing about the cash that needs attention")
    public record NeedsAttention(
            @Schema(description = "What kind of thing it is")
            CashAttentionCode code,

            @Schema(description = "Why it stands apart from its kind; null when there is no such reason") @Nullable
            CashAttentionReason reason,

            @Schema(description = "The money at stake", example = "35.00") @Nullable
            BigDecimal amount,

            @Schema(description = "How many sources make it up; null for a ledger balance", example = "2") @Nullable
            Integer count,

            @Schema(description = "The date it has stood since; null when it has none") @Nullable
            LocalDate since) {}

    /** The CASH walk-in house account. */
    @Schema(name = "CashPositionUnpaidWalkInSales", description = "Unpaid walk-in sales on the CASH house account")
    public record UnpaidWalkInSales(
            @Schema(description = "What is still owed on the CASH account", example = "86.00")
            BigDecimal balance,

            @Schema(description = "Open walk-in invoices", example = "3")
            int invoiceCount,

            @Schema(
                    description = "False when accounting has not yet received the CASH account: the zero cannot be"
                            + " vouched for",
                    example = "true")
            boolean houseAccountKnown) {}
}
