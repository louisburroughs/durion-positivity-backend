package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.JournalEntryType;
import com.positivity.accounting.internal.enums.ManualJEReasonCode;
import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

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
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = "lines")
@Entity
@Table(name = "journal_entry", indexes = {
        @Index(name = "idx_journal_entry_status", columnList = "status"),
        @Index(name = "idx_journal_entry_entry_type", columnList = "entry_type"),
        @Index(name = "idx_journal_entry_transaction_date", columnList = "transaction_date"),
        @Index(name = "idx_journal_entry_source_event", columnList = "source_event_id"),
        @Index(name = "idx_journal_entry_posted_at", columnList = "posted_at")
})
public class JournalEntry {

    @EqualsAndHashCode.Include
    @Id
    @Column(name = "journal_entry_id", nullable = false, columnDefinition = "UUID")
    private UUID journalEntryId;

    @PrePersist
    public void onPrePersist() {
        if (journalEntryId == null) {
            journalEntryId = UUIDv7Generator.generate();
        }
        Instant now = Instant.now();
        this.createdAt = now;
        this.modifiedAt = now;
    }

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

    @Column(name = "source_event_id")
    private UUID sourceEventId;

    @Column(name = "source_event_type", length = 100)
    private String sourceEventType;

    @Column(name = "posting_rule_set_id")
    private UUID postingRuleSetId;

    @Column(name = "posting_rule_version_id")
    private UUID postingRuleVersionId;

    // Manual JE fields (required when entryType = MANUAL)
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 50)
    private ManualJEReasonCode reasonCode;

    @Column(name = "justification", length = 1000)
    private String justification;

    @Column(name = "reversal_journal_entry_id")
    private UUID reversalJournalEntryId;

    @Column(name = "reversed_by_journal_entry_id")
    private UUID reversedByJournalEntryId;

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
