package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.positivity.accounting.internal.enums.JournalEntryStatus;

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
public class JournalEntryResponse {

    private UUID journalEntryId;
    private JournalEntryStatus status;
    private LocalDateTime transactionDate;
    private String description;
    private UUID sourceEventId;
    private String sourceEventType;
    private UUID postingRuleSetId;
    private UUID postingRuleVersionId;
    private UUID reversalJournalEntryId;
    private UUID reversedByJournalEntryId;
    private BigDecimal totalDebits;
    private BigDecimal totalCredits;
    private Boolean isBalanced;
    private List<JournalEntryLineResponse> lines;
    private Instant createdAt;
    private String createdBy;
    private Instant modifiedAt;
    private String modifiedBy;
    private Instant postedAt;
    private String postedBy;
    private Instant reversedAt;

    /**
     * Nested DTO for journal entry line response.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JournalEntryLineResponse {
        private UUID lineId;
        private Integer lineNumber;
        private UUID glAccountId;
        private String accountCode;
        private String accountName;
        private BigDecimal debitAmount;
        private BigDecimal creditAmount;
        private String description;
        private Map<String, String> dimensions;
    }
}
