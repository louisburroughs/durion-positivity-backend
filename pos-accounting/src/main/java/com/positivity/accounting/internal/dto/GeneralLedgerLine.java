package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * One chronological POSTED journal line within a General Ledger account section.
 *
 * Lines are ordered by transaction date then entry number. {@code runningBalance}
 * is the account's cumulative signed balance (debit positive) up to and
 * including this line, seeded by the section's opening balance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Chronological POSTED journal line with running balance for a GL account")
public class GeneralLedgerLine {

    /**
     * Journal entry identifier.
     */
    @Schema(
            description = "Journal entry UUID",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NonNull
    private UUID journalEntryId;

    /**
     * Human-readable journal entry number (chart/sequence code).
     */
    @Schema(description = "Journal entry number", example = "JE-202606-0042", requiredMode = NOT_REQUIRED)
    private String entryNumber;

    /**
     * Transaction (accounting) date of the line.
     */
    @Schema(description = "Transaction date", example = "2026-06-15", requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate transactionDate;

    /**
     * Description/narrative for the journal line.
     */
    @Schema(
            description = "Description/narrative for the journal line",
            example = "Recognize receivable",
            requiredMode = NOT_REQUIRED)
    private String description;

    /**
     * Debit amount (null if this line is a credit).
     */
    @Schema(description = "Debit amount (null if credit)", example = "1250.00", requiredMode = NOT_REQUIRED)
    private BigDecimal debitAmount;

    /**
     * Credit amount (null if this line is a debit).
     */
    @Schema(description = "Credit amount (null if debit)", example = "1250.00", requiredMode = NOT_REQUIRED)
    private BigDecimal creditAmount;

    /**
     * Cumulative signed account balance (debit positive) through this line.
     */
    @Schema(
            description = "Cumulative signed account balance (debit positive) through this line",
            example = "80000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal runningBalance;

    /**
     * Source event type that triggered this journal entry.
     */
    @Schema(description = "Source event type", example = "INVOICE_POSTED", requiredMode = NOT_REQUIRED)
    private String sourceEventType;
}
