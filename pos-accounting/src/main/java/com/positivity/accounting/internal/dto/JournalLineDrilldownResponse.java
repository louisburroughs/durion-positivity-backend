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
 * Drilldown response showing an individual journal line for an account.
 *
 * Each response represents one journal line (debit or credit).
 * Service returns a list of these for all lines affecting an account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Drilldown of an individual journal line affecting an account")
public class JournalLineDrilldownResponse {

    /**
     * Journal Entry ID.
     */
    @NonNull
    @Schema(
            description = "Journal entry UUID",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID journalEntryId;

    /**
     * Transaction date.
     */
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @Schema(description = "Transaction date", example = "2026-01-15", requiredMode = REQUIRED)
    private LocalDate transactionDate;

    /**
     * Description/narrative for the journal line.
     */
    @Schema(description = "Description/narrative for the journal line", example = "Recognize receivable", requiredMode = NOT_REQUIRED)
    private String description;

    /**
     * Debit amount (null if credit).
     */
    @Schema(description = "Debit amount (null if credit)", example = "1250.00", requiredMode = NOT_REQUIRED)
    private BigDecimal debitAmount;

    /**
     * Credit amount (null if debit).
     */
    @Schema(description = "Credit amount (null if debit)", example = "1250.00", requiredMode = NOT_REQUIRED)
    private BigDecimal creditAmount;

    /**
     * Source event ID that triggered this journal entry.
     */
    @Schema(
            description = "Source event UUID that triggered this journal entry",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID sourceEventId;

    /**
     * Source event type (e.g., "INVOICE_POSTED", "PAYMENT_RECEIVED").
     */
    @Schema(description = "Source event type", example = "INVOICE_POSTED", requiredMode = NOT_REQUIRED)
    private String sourceEventType;
}
