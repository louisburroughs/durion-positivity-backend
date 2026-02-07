package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.ReconciliationStatus;
import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reconciliation entity - manages bank/cash reconciliation sessions.
 * 
 * Lifecycle: IN_PROGRESS → FINALIZED (or CANCELLED)
 * 
 * Process: Import statement → Match transactions → Create adjustments →
 * Finalize
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Reconciliation</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = { "statementLines", "glTransactions", "adjustments" })
@Entity
@Table(name = "reconciliation", indexes = {
        @Index(name = "idx_reconciliation_account", columnList = "gl_account_id"),
        @Index(name = "idx_reconciliation_status", columnList = "status"),
        @Index(name = "idx_reconciliation_period", columnList = "period_start_date, period_end_date")
})
public class Reconciliation {

    @EqualsAndHashCode.Include
    @Id
    @Column(name = "reconciliation_id", nullable = false, columnDefinition = "UUID")
    private UUID reconciliationId;

    @PrePersist
    public void generateId() {
        if (reconciliationId == null) {
            reconciliationId = UUIDv7Generator.generate();
        }
    }

    @Column(name = "gl_account_id", nullable = false)
    private UUID glAccountId;

    @Column(name = "account_code", length = 20)
    private String accountCode;

    @Column(name = "account_name", length = 100)
    private String accountName;

    @Column(name = "period_start_date", nullable = false)
    private LocalDateTime periodStartDate;

    @Column(name = "period_end_date", nullable = false)
    private LocalDateTime periodEndDate;

    @Column(name = "statement_date", nullable = false)
    private LocalDateTime statementDate;

    @Column(name = "statement_ending_balance", precision = 19, scale = 4, nullable = false)
    private BigDecimal statementEndingBalance;

    @Column(name = "gl_ending_balance", precision = 19, scale = 4, nullable = false)
    private BigDecimal glEndingBalance;

    @Column(name = "difference", precision = 19, scale = 4, nullable = false)
    private BigDecimal difference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ReconciliationStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "statement_lines")
    private List<StatementLine> statementLines = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gl_transactions")
    private List<GLTransaction> glTransactions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "adjustments")
    private List<Adjustment> adjustments = new ArrayList<>();

    // Audit fields
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "finalized_by", length = 50)
    private String finalizedBy;

    // Nested classes for JSONB storage
    @Getter
    @Setter
    @NoArgsConstructor
    public static class StatementLine {
        private String lineId;
        private Integer lineNumber;
        private LocalDateTime transactionDate;
        private String description;
        private BigDecimal amount;
        private String referenceNumber;
        private List<String> matchedGLTransactionIds = new ArrayList<>();
        private String matchStatus;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class GLTransaction {
        private String glTransactionId;
        private String journalEntryId;
        private LocalDateTime transactionDate;
        private String description;
        private BigDecimal amount;
        private List<String> matchedStatementLineIds = new ArrayList<>();
        private String matchStatus;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Adjustment {
        private String adjustmentId;
        private String journalEntryId;
        private String adjustmentType;
        private BigDecimal amount;
        private String description;
    }

    public Reconciliation(UUID reconciliationId) {
        this.reconciliationId = reconciliationId;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (status == null) {
            status = ReconciliationStatus.IN_PROGRESS;
        }
        if (difference == null && statementEndingBalance != null && glEndingBalance != null) {
            difference = statementEndingBalance.subtract(glEndingBalance);
        }
    }
}
