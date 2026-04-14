package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Journal Entry Line - individual GL account posting within a JournalEntry.
 *
 * Each line posts either a debit or credit (not both) to a specific GL account.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Journal Entry</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"journalEntry", "glAccount"})
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "journal_entry_line",
        indexes = {
            @Index(name = "idx_journal_entry_line_je", columnList = "journal_entry_id"),
            @Index(name = "idx_journal_entry_line_gl_account", columnList = "gl_account_id")
        })
public class JournalEntryLine {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "line_id", nullable = false, columnDefinition = "UUID")
    private UUID lineId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private JournalEntry journalEntry;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gl_account_id", nullable = false)
    private GLAccount glAccount;

    @Column(name = "account_code", length = 20)
    private String accountCode;

    @Column(name = "account_name", length = 100)
    private String accountName;

    @Column(name = "debit_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal debitAmount = BigDecimal.ZERO;

    @Column(name = "credit_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal creditAmount = BigDecimal.ZERO;

    @Column(name = "description", length = 500)
    private String description;

    /**
     * Dimensions for this line (businessUnitId, locationId, departmentId,
     * costCenterId).
     * Stored as JSON (H2) or JSONB (PostgreSQL) based on dialect.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dimensions")
    private Map<String, String> dimensions;

    // Scalar compatibility accessors for journalEntryId
    @Transient
    public UUID getJournalEntryId() {
        return journalEntry != null ? journalEntry.getJournalEntryId() : null;
    }

    @Transient
    public void setJournalEntryId(UUID journalEntryId) {
        this.journalEntry = journalEntryId != null ? new JournalEntry(journalEntryId) : null;
    }

    // Scalar compatibility accessors for glAccountId
    @Transient
    public UUID getGlAccountId() {
        return glAccount != null ? glAccount.getGlAccountId() : null;
    }

    public void setGlAccountId(UUID glAccountId) {
        this.glAccount = glAccountId != null ? new GLAccount(glAccountId) : null;
    }
}
