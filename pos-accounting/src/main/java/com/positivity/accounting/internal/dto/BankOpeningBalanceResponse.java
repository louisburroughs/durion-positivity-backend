package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.BankOpeningItemType;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The outcome of a bank opening balance command (#2572, OI-10): the balances, the journal entry by id and by
 * number (ADR-0064), and the ledger line of each outstanding item, which bank reconciliation registers as an
 * outstanding item in the account's first reconciliation.
 */
@Schema(description = "A bank account's opening balance as posted")
public record BankOpeningBalanceResponse(
        @Schema(description = "The bank account", requiredMode = REQUIRED)
        UUID glAccountId,

        @Schema(description = "The bank account's code", example = "1000", requiredMode = REQUIRED)
        String accountCode,

        @Schema(description = "The cutover date the entry is dated on", requiredMode = REQUIRED)
        LocalDate asOfDate,

        @Schema(
                description = "The bank's balance at the end of asOfDate",
                example = "10000.00",
                requiredMode = REQUIRED)
        BigDecimal statementBalance,

        @Schema(
                description = "The ledger balance the entry sets: statement balance + deposits in transit −"
                        + " outstanding checks",
                example = "10750.00",
                requiredMode = REQUIRED)
        BigDecimal bookBalance,

        @Schema(
                description = "The ISO 4217 code of both balances and every item amount",
                example = "USD",
                requiredMode = REQUIRED)
        String currencyCode,

        @ArraySchema(
                arraySchema =
                        @Schema(
                                description = "The outstanding items, each with its own bank line; empty when there"
                                        + " are none",
                                requiredMode = REQUIRED))
        List<Item> outstandingItems,

        @Schema(description = "The journal entry the command posted", requiredMode = REQUIRED)
        UUID journalEntryId,

        @Schema(description = "That entry's number", example = "JE-202510-1", requiredMode = REQUIRED)
        String journalEntryNumber,

        @Schema(description = "True when this answers a replayed requestId with the first result")
        boolean replayed) {

    /** An outstanding item and the bank line it posted as. */
    @Schema(name = "BankOpeningBalanceItem", description = "An outstanding item of the opening and its bank line")
    public record Item(
            @Schema(description = "What the item is", requiredMode = REQUIRED)
            BankOpeningItemType type,

            @Schema(description = "The check number or deposit reference", example = "1043", requiredMode = REQUIRED)
            String reference,

            @Schema(description = "The date the check was written or the deposit made", requiredMode = REQUIRED)
            LocalDate itemDate,

            @Schema(description = "The item's amount", example = "450.00", requiredMode = REQUIRED)
            BigDecimal amount,

            @Schema(
                    description = "The bank line the item posted as: the glLineId to register as an outstanding item"
                            + " in the account's first bank reconciliation",
                    requiredMode = REQUIRED)
            UUID glLineId) {}
}
