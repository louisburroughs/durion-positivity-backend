package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;
import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

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
public class JournalLineDrilldownResponse {

    /**
     * Journal Entry ID.
     */
    @NonNull
    private UUID journalEntryId;

    /**
     * Transaction date.
     */
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate transactionDate;

    /**
     * Description/narrative for the journal line.
     */
    private String description;

    /**
     * Debit amount (null if credit).
     */
    private BigDecimal debitAmount;

    /**
     * Credit amount (null if debit).
     */
    private BigDecimal creditAmount;

    /**
     * Source event ID that triggered this journal entry.
     */
    private UUID sourceEventId;

    /**
     * Source event type (e.g., "INVOICE_POSTED", "PAYMENT_RECEIVED").
     */
    private String sourceEventType;
}
