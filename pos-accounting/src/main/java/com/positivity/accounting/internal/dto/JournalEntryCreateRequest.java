package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTO for creating a new Journal Entry.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Journal Entry Request</a>
 */
public class JournalEntryCreateRequest {

    private LocalDate transactionDate;
    private String description;
    private String sourceEventId;
    private String sourceEventType;
    private String postingRuleSetId;
    private String postingRuleVersionId;
    private List<JournalEntryLineRequest> lines;

    // Constructors
    public JournalEntryCreateRequest() {
    }

    // Getters and Setters
    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(LocalDate transactionDate) {
        this.transactionDate = transactionDate;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public void setSourceEventId(String sourceEventId) {
        this.sourceEventId = sourceEventId;
    }

    public String getSourceEventType() {
        return sourceEventType;
    }

    public void setSourceEventType(String sourceEventType) {
        this.sourceEventType = sourceEventType;
    }

    public String getPostingRuleSetId() {
        return postingRuleSetId;
    }

    public void setPostingRuleSetId(String postingRuleSetId) {
        this.postingRuleSetId = postingRuleSetId;
    }

    public String getPostingRuleVersionId() {
        return postingRuleVersionId;
    }

    public void setPostingRuleVersionId(String postingRuleVersionId) {
        this.postingRuleVersionId = postingRuleVersionId;
    }

    public List<JournalEntryLineRequest> getLines() {
        return lines;
    }

    public void setLines(List<JournalEntryLineRequest> lines) {
        this.lines = lines;
    }

    /**
     * Nested DTO for journal entry lines.
     */
    public static class JournalEntryLineRequest {
        private String glAccountId;
        private BigDecimal debitAmount;
        private BigDecimal creditAmount;
        private String description;
        private Map<String, String> dimensions;

        public JournalEntryLineRequest() {
        }

        public String getGlAccountId() {
            return glAccountId;
        }

        public void setGlAccountId(String glAccountId) {
            this.glAccountId = glAccountId;
        }

        public BigDecimal getDebitAmount() {
            return debitAmount;
        }

        public void setDebitAmount(BigDecimal debitAmount) {
            this.debitAmount = debitAmount;
        }

        public BigDecimal getCreditAmount() {
            return creditAmount;
        }

        public void setCreditAmount(BigDecimal creditAmount) {
            this.creditAmount = creditAmount;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Map<String, String> getDimensions() {
            return dimensions;
        }

        public void setDimensions(Map<String, String> dimensions) {
            this.dimensions = dimensions;
        }
    }
}
