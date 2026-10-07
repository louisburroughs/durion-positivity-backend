package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.DepositStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A bank deposit of drawer cash (CAP:550 S18, #2514): its amounts, its entry by business reference (ADR-0064), the
 * sessions it took with their bag numbers, and its reversal once reversed. Every amount is in {@code currencyCode}
 * (ADR-0067 R-1).
 */
@Schema(description = "A bank deposit of whole closed register sessions' drawer cash")
public record DepositResponse(
        @Schema(description = "The deposit") UUID depositId,
        @Schema(description = "RECORDED, or REVERSED once its entry is reversed") DepositStatus status,
        @Schema(description = "The bank account the cash went to") UUID bankGlAccountId,
        @Schema(description = "The bank account's number", example = "1000") String bankAccountCode,
        @Schema(description = "The day the cash reached the bank: the entry's date") LocalDate depositDate,
        @Schema(description = "The bank debit: the total of the sessions' bank drops", example = "1197.00")
                BigDecimal amount,
        @Schema(description = "The sessions' expected cash: the credit to 1090 Undeposited Funds", example = "1240.00")
                BigDecimal expectedCash,
        @Schema(
                        description = "The sessions' clearing net, debit positive: the deposit debits 1095 Register Cash"
                                + " Clearing by its size when it is negative and credits it when positive",
                        example = "-43.00")
                BigDecimal clearingNet,
        @Schema(description = "ISO 4217 code of every amount: the functional currency", example = "USD")
                String currencyCode,
        @Schema(description = "The bank's deposit slip number", example = "DS-20261008-01") @Nullable
                String depositSlipReference,
        @Schema(description = "The deposit's entry") UUID journalEntryId,
        @Schema(description = "The deposit's entry number (the business reference)", example = "JE-202610-41")
                String journalEntryNumber,
        @Schema(description = "The sessions the deposit took, oldest close first") List<Session> sessions,
        @Schema(description = "The reversal's entry; null until reversed") @Nullable UUID reversalJournalEntryId,
        @Schema(description = "The reversal's entry number; null until reversed", example = "JE-202610-44") @Nullable
                String reversalJournalEntryNumber,
        @Schema(description = "The reversal's date; null until reversed") @Nullable LocalDate reversalDate,
        @Schema(description = "Why it was reversed; null until reversed") @Nullable String reversalReason,
        @Schema(description = "Who recorded it") String recordedBy,
        @Schema(description = "Who reversed it; null until reversed") @Nullable String reversedBy,
        @Schema(description = "When it was reversed; null until reversed") @Nullable Instant reversedAt,
        @Schema(description = "True when this answers a replayed requestId with the first result") boolean replayed) {

    /** One session the deposit took. */
    @Schema(description = "A session the deposit took whole")
    public record Session(
            @Schema(description = "The register session") UUID sessionId,
            @Schema(description = "The register", example = "T-7") String terminalId,
            @Schema(description = "The session's location; null when it carried none") @Nullable UUID locationId,
            @Schema(description = "When the session closed") Instant closedAt,
            @Schema(description = "The session's bank drops: its share of the bank debit", example = "1197.00")
                    BigDecimal depositAmount,
            @Schema(description = "The session's expected cash", example = "1240.00") BigDecimal expectedCash,
            @Schema(description = "The session's clearing net, debit positive", example = "-43.00")
                    BigDecimal clearingNet,
            @Schema(description = "The bag numbers of the session's bank drops") List<String> bagNumbers) {}
}
