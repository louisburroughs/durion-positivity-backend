package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.JournalEntryStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTO for Journal Entry response.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Journal Entry Response</a>
 */
public class JournalEntryResponse {

    private String journalEntryId;
    private JournalEntryStatus status;
    private LocalDate transactionDate;
    private String description;
    private String sourceEventId;
    private String sourceEventType;
    private String postingRuleSetId;
    private String postingRuleVersionId;
    private String reversalJournalEntryId;
    private String reversedByJournalEntryId;
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

    // Constructors
    public JournalEntryResponse() {
    }

    // Getters and Setters
    public String getJournalEntryId() {
        return journalEntryId;
    }

    public void setJournalEntryId(String journalEntryId) {
        this.journalEntryId = journalEntryId;
    }

    public JournalEntryStatus getStatus() {
        return status;
    }

    public void setStatus(JournalEntryStatus status) {
        this.status = status;
    }

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

    public String getReversalJournalEntryId() {
        return reversalJournalEntryId;
    }

    public void setReversalJournalEntryId(String reversalJournalEntryId) {
        this.reversalJournalEntryId = reversalJournalEntryId;
    }

    public String getReversedByJournalEntryId() {
        return reversedByJournalEntryId;
    }

    public void setReversedByJournalEntryId(String reversedByJournalEntryId) {
        this.reversedByJournalEntryId = reversedByJournalEntryId;
    }

    public BigDecimal getTotalDebits() {
        return totalDebits;
    }

    public void setTotalDebits(BigDecimal totalDebits) {
        this.totalDebits = totalDebits;
    }

    public BigDecimal getTotalCredits() {
        return totalCredits;
    }

    public void setTotalCredits(BigDecimal totalCredits) {
        this.totalCredits = totalCredits;
    }

    public Boolean getIsBalanced() {
        return isBalanced;
    }

    public void setIsBalanced(Boolean isBalanced) {
        this.isBalanced = isBalanced;
    }

    public List<JournalEntryLineResponse> getLines() {
        return lines;
    }

    public void setLines(List<JournalEntryLineResponse> lines) {
        this.lines = lines;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(Instant modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public String getModifiedBy() {
        return modifiedBy;
    }

    public void setModifiedBy(String modifiedBy) {
        this.modifiedBy = modifiedBy;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(Instant postedAt) {
        this.postedAt = postedAt;
    }

    public String getPostedBy() {
        return postedBy;
    }

    public void setPostedBy(String postedBy) {
        this.postedBy = postedBy;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public void setReversedAt(Instant reversedAt) {
        this.reversedAt = reversedAt;
    }

    /**
     * Nested DTO for journal entry line response.
     */
    public static class JournalEntryLineResponse {
        private String lineId;
        private Integer lineNumber;
        private String glAccountId;
        private String accountCode;
        private String accountName;
        private BigDecimal debitAmount;
        private BigDecimal creditAmount;
        private String description;
        private Map<String, String> dimensions;

        public JournalEntryLineResponse() {
        }

        public String getLineId() {
            return lineId;
        }

        public void setLineId(String lineId) {
            this.lineId = lineId;
        }

        public Integer getLineNumber() {
            return lineNumber;
        }

        public void setLineNumber(Integer lineNumber) {
            this.lineNumber = lineNumber;
        }

        public String getGlAccountId() {
            return glAccountId;
        }

        public void setGlAccountId(String glAccountId) {
            this.glAccountId = glAccountId;
        }

        public String getAccountCode() {
            return accountCode;
        }

        public void setAccountCode(String accountCode) {
            this.accountCode = accountCode;
        }

        public String getAccountName() {
            return accountName;
        }

        public void setAccountName(String accountName) {
            this.accountName = accountName;
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
