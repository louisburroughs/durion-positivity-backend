package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.JournalEntryType;
import com.positivity.accounting.internal.enums.ManualJEReasonCode;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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
@ToString(exclude = {"lines", "postingRuleSet", "postingRuleVersion", "reversalJournalEntry", "reversedByJournalEntry"})
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "journal_entry",
        indexes = {
            @Index(name = "idx_journal_entry_status", columnList = "status"),
            @Index(name = "idx_journal_entry_entry_type", columnList = "entry_type"),
            @Index(name = "idx_journal_entry_transaction_date", columnList = "transaction_date"),
            @Index(name = "idx_journal_entry_source_event", columnList = "source_event_id"),
            @Index(name = "idx_journal_entry_posted_at", columnList = "posted_at")
        })
public class JournalEntry {

    private static final String SYSTEM = "SYSTEM";

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "journal_entry_id", nullable = false, columnDefinition = "UUID")
    private UUID journalEntryId;

    public JournalEntry(UUID journalEntryId) {
        this.journalEntryId = journalEntryId;
    }

    @PrePersist
    public void onPrePersist() {
        String currentUser = SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
        this.createdBy = currentUser;
        this.modifiedBy = currentUser;

        // Initialize line relationships: set journalEntryId and lineNumber
        initializeLines();
    }

    /**
     * Initialize lines with proper parent reference and sequential line numbers.
     * Called automatically during @PrePersist, but can be called manually if
     * needed.
     * Public to allow explicit initialization in service layer before persistence.
     */
    public void initializeLines() {
        if (lines != null && !lines.isEmpty()) {
            int lineNumber = 1;
            for (JournalEntryLine line : lines) {
                // Set parent reference if not already set
                if (line.getJournalEntry() == null) {
                    line.setJournalEntry(this);
                }
                // Set line number if not already set
                if (line.getLineNumber() == null) {
                    line.setLineNumber(lineNumber++);
                }
            }
        }
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "posting_rule_set_id")
    private PostingRuleSet postingRuleSet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "posting_rule_version_id")
    private PostingRuleVersion postingRuleVersion;

    // Manual JE fields (required when entryType = MANUAL)
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 50)
    private ManualJEReasonCode reasonCode;

    @Column(name = "justification", length = 1000)
    private String justification;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversal_journal_entry_id")
    private JournalEntry reversalJournalEntry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversed_by_journal_entry_id")
    private JournalEntry reversedByJournalEntry;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<JournalEntryLine> lines = new ArrayList<>();

    @Column(name = "total_debits", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalDebits = BigDecimal.ZERO;

    @Column(name = "total_credits", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalCredits = BigDecimal.ZERO;

    @Column(name = "is_balanced", nullable = false)
    private Boolean isBalanced = false;

    // Audit fields
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant updatedAt;

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
        this.modifiedBy = SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }

    /**
     * Calculate and update total debits/credits and balance status.
     * Call after adding/updating lines.
     */
    public void calculateTotals() {
        this.totalDebits =
                lines.stream().map(JournalEntryLine::getDebitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        this.totalCredits =
                lines.stream().map(JournalEntryLine::getCreditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

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

    /**
     * Add a line to this journal entry.
     * The journalEntryId and lineNumber will be set automatically during
     * persistence
     * via the @PrePersist hook.
     *
     * @param line the line to add
     */
    public void addLine(JournalEntryLine line) {
        if (line == null) {
            throw new IllegalArgumentException("Cannot add null line");
        }
        if (this.lines == null) {
            this.lines = new ArrayList<>();
        }
        this.lines.add(line);
    }

    // Scalar compatibility accessors for postingRuleSetId
    @Transient
    public UUID getPostingRuleSetId() {
        return postingRuleSet != null ? postingRuleSet.getPostingRuleSetId() : null;
    }

    public void setPostingRuleSetId(UUID postingRuleSetId) {
        this.postingRuleSet = postingRuleSetId != null ? new PostingRuleSet(postingRuleSetId) : null;
    }

    // Scalar compatibility accessors for postingRuleVersionId
    @Transient
    public UUID getPostingRuleVersionId() {
        return postingRuleVersion != null ? postingRuleVersion.getVersionId() : null;
    }

    public void setPostingRuleVersionId(UUID postingRuleVersionId) {
        this.postingRuleVersion = postingRuleVersionId != null ? new PostingRuleVersion(postingRuleVersionId) : null;
    }

    // Scalar compatibility accessors for reversalJournalEntryId
    @Transient
    public UUID getReversalJournalEntryId() {
        return reversalJournalEntry != null ? reversalJournalEntry.getJournalEntryId() : null;
    }

    public void setReversalJournalEntryId(UUID reversalJournalEntryId) {
        this.reversalJournalEntry = reversalJournalEntryId != null ? new JournalEntry(reversalJournalEntryId) : null;
    }

    // Scalar compatibility accessors for reversedByJournalEntryId
    @Transient
    public UUID getReversedByJournalEntryId() {
        return reversedByJournalEntry != null ? reversedByJournalEntry.getJournalEntryId() : null;
    }

    public void setReversedByJournalEntryId(UUID reversedByJournalEntryId) {
        this.reversedByJournalEntry =
                reversedByJournalEntryId != null ? new JournalEntry(reversedByJournalEntryId) : null;
    }
}
