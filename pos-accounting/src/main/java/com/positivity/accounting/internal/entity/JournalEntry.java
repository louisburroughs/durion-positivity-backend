package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.JournalEntryType;
import com.positivity.accounting.internal.enums.ManualJEReasonCode;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Journal Entry - balanced set of GL postings.
 * 
 * Must satisfy: sum(debitAmount) == sum(creditAmount) within tolerance ±0.0001
 * 
 * Once POSTED, entry and lines are immutable.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Journal Entry</a>
 */
@Entity
@Table(name = "journal_entry", indexes = {
        @Index(name = "idx_journal_entry_status", columnList = "status"),
        @Index(name = "idx_journal_entry_entry_type", columnList = "entry_type"),
        @Index(name = "idx_journal_entry_transaction_date", columnList = "transaction_date"),
        @Index(name = "idx_journal_entry_source_event", columnList = "source_event_id"),
        @Index(name = "idx_journal_entry_posted_at", columnList = "posted_at")
})
public class JournalEntry {

    @Id
    @Column(name = "journal_entry_id", length = 50, nullable = false)
    private String journalEntryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private JournalEntryStatus status = JournalEntryStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", length = 20, nullable = false)
    private JournalEntryType entryType = JournalEntryType.EVENT_DRIVEN;

    @Column(name = "transaction_date", nullable = false)
    private LocalDateTime transactionDate;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "source_event_id", length = 50)
    private String sourceEventId;

    @Column(name = "source_event_type", length = 100)
    private String sourceEventType;

    @Column(name = "posting_rule_set_id", length = 50)
    private String postingRuleSetId;

    @Column(name = "posting_rule_version_id", length = 50)
    private String postingRuleVersionId;

    // Manual JE fields (required when entryType = MANUAL)
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 50)
    private ManualJEReasonCode reasonCode;

    @Column(name = "justification", length = 1000)
    private String justification;

    @Column(name = "reversal_journal_entry_id", length = 50)
    private String reversalJournalEntryId;

    @Column(name = "reversed_by_journal_entry_id", length = 50)
    private String reversedByJournalEntryId;

    @OneToMany(mappedBy = "journalEntryId", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<JournalEntryLine> lines = new ArrayList<>();

    @Column(name = "total_debits", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalDebits = BigDecimal.ZERO;

    @Column(name = "total_credits", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalCredits = BigDecimal.ZERO;

    @Column(name = "is_balanced", nullable = false)
    private Boolean isBalanced = false;

    // Audit fields
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "modified_at", nullable = false)
    private Instant modifiedAt;

    @Column(name = "modified_by", length = 50, nullable = false)
    private String modifiedBy;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "posted_by", length = 50)
    private String postedBy;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    // Constructors
    public JournalEntry() {
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

    public JournalEntryType getEntryType() {
        return entryType;
    }

    public void setEntryType(JournalEntryType entryType) {
        this.entryType = entryType;
    }

    public LocalDateTime getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(LocalDateTime transactionDate) {
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

    public ManualJEReasonCode getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(ManualJEReasonCode reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getJustification() {
        return justification;
    }

    public void setJustification(String justification) {
        this.justification = justification;
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

    public List<JournalEntryLine> getLines() {
        return lines;
    }

    public void setLines(List<JournalEntryLine> lines) {
        this.lines = lines;
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

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.modifiedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.modifiedAt = Instant.now();
    }

    /**
     * Calculate and update total debits/credits and balance status.
     * Call after adding/updating lines.
     */
    public void calculateTotals() {
        this.totalDebits = lines.stream()
                .map(JournalEntryLine::getDebitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        this.totalCredits = lines.stream()
                .map(JournalEntryLine::getCreditAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Balance check: difference must be within ±0.0001
        BigDecimal difference = totalDebits.subtract(totalCredits).abs();
        this.isBalanced = difference.compareTo(new BigDecimal("0.0001")) < 0;
    }

    /**
     * Check if this JE is immutable (cannot be edited).
     * 
     * @return true if status is POSTED or REVERSED
     */
    @Transient
    public boolean isImmutable() {
        return status == JournalEntryStatus.POSTED || status == JournalEntryStatus.REVERSED;
    }
}
