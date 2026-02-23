package com.positivity.accounting.internal.entity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

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
@ToString
@Entity
@Table(name = "journal_entry_line", indexes = {
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

    @PrePersist
    public void generateId() {
    }

    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    @Column(name = "gl_account_id", nullable = false)
    private UUID glAccountId;

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

}
