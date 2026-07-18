package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.JournalEntryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for Journal Entry response.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Journal Entry Response</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Journal entry response payload")
public class JournalEntryResponse {

    @Schema(description = "Journal entry UUID", example = "01936e5e-7890-7a3d-8b6e-4d5678901234")
    private UUID journalEntryId;

    @Schema(
            description = "Human-readable posted-entry number in the format JE-{YYYYMM}-{seq}, "
                    + "sequential within the entry's transaction month and assigned at posting time. "
                    + "Null for DRAFT and PENDING entries and for entries posted before numbering existed.",
            example = "JE-202607-1",
            nullable = true)
    private String entryNumber;

    @Schema(description = "Journal entry status", example = "DRAFT")
    private JournalEntryStatus status;

    @Schema(description = "Transaction date and time", example = "2026-01-15T10:30:00")
    private LocalDateTime transactionDate;

    @Schema(description = "Journal entry description", example = "Invoice accrual posting")
    private String description;

    @Schema(description = "Source event UUID", example = "01936e5c-1234-7a3d-8b6e-123456789012")
    private UUID sourceEventId;

    @Schema(description = "Source event type", example = "INVOICE_RECEIVED")
    private String sourceEventType;

    @Schema(description = "Posting rule set UUID")
    private UUID postingRuleSetId;

    @Schema(description = "Posting rule version UUID")
    private UUID postingRuleVersionId;

    @Schema(description = "Original journal entry UUID when this entry is a reversal")
    private UUID reversalJournalEntryId;

    @Schema(description = "Reversal journal entry UUID when this entry has been reversed")
    private UUID reversedByJournalEntryId;

    @Schema(description = "Total debits", example = "1250.00")
    private BigDecimal totalDebits;

    @Schema(description = "Total credits", example = "1250.00")
    private BigDecimal totalCredits;

    @Schema(description = "Whether total debits equal total credits", example = "true")
    private Boolean isBalanced;

    @Schema(description = "Journal entry lines")
    private List<JournalEntryLineResponse> lines;

    @Schema(description = "Created timestamp", example = "2026-01-15T10:31:00Z")
    private Instant createdAt;

    @Schema(description = "Created by username", example = "accounting.user")
    private String createdBy;

    @Schema(description = "Last modified timestamp", example = "2026-01-15T10:32:00Z")
    private Instant modifiedAt;

    @Schema(description = "Last modified by username", example = "accounting.user")
    private String modifiedBy;

    @Schema(description = "Posted timestamp", example = "2026-01-15T10:40:00Z")
    private Instant postedAt;

    @Schema(description = "Posted by username", example = "accounting.manager")
    private String postedBy;

    @Schema(description = "Reversed timestamp", example = "2026-01-16T08:20:00Z")
    private Instant reversedAt;

    /**
     * Nested DTO for journal entry line response.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Journal entry line response")
    public static class JournalEntryLineResponse {
        @Schema(description = "Line UUID", example = "01936e60-1234-7a3d-8b6e-6f7890123456")
        private UUID lineId;

        @Schema(description = "Line sequence number", example = "1")
        private Integer lineNumber;

        @Schema(description = "GL account UUID", example = "01936e5d-1234-7a3d-8b6e-3c4567890123")
        private UUID glAccountId;

        @Schema(description = "GL account code", example = "1100-000")
        private String accountCode;

        @Schema(description = "GL account name", example = "Accounts Receivable")
        private String accountName;

        @Schema(description = "Debit amount", example = "1250.00")
        private BigDecimal debitAmount;

        @Schema(description = "Credit amount", example = "0.00")
        private BigDecimal creditAmount;

        @Schema(description = "Line description", example = "Recognize receivable")
        private String description;

        @Schema(description = "Optional dimensional attributes", example = "{\"department\":\"SERVICE\"}")
        private Map<String, String> dimensions;
    }
}
