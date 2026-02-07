package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for creating a new Journal Entry.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Journal Entry Request</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JournalEntryCreateRequest {

    private LocalDateTime transactionDate;
    private String description;
    private UUID sourceEventId;
    private String sourceEventType;
    private UUID postingRuleSetId;
    private UUID postingRuleVersionId;
    private List<JournalEntryLineRequest> lines;

    /**
     * Nested DTO for journal entry lines.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JournalEntryLineRequest {
        private UUID glAccountId;
        private BigDecimal debitAmount;
        private BigDecimal creditAmount;
        private String description;
        private Map<String, String> dimensions;
    }
}
