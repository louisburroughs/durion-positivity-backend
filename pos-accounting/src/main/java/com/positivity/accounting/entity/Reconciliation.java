package com.positivity.accounting.entity;

import com.positivity.accounting.enums.ReconciliationStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
@Entity
@Table(name = "reconciliation", indexes = {
        @Index(name = "idx_reconciliation_account", columnList = "gl_account_id"),
        @Index(name = "idx_reconciliation_status", columnList = "status"),
        @Index(name = "idx_reconciliation_period", columnList = "period_start_date, period_end_date")
})
public class Reconciliation {

    @Id
    @Column(name = "reconciliation_id", length = 50, nullable = false)
    private String reconciliationId;

    @Column(name = "gl_account_id", length = 50, nullable = false)
    private String glAccountId;

    @Column(name = "account_code", length = 20)
    private String accountCode;

    @Column(name = "account_name", length = 100)
    private String accountName;

    @Column(name = "period_start_date", nullable = false)
    private LocalDate periodStartDate;

    @Column(name = "period_end_date", nullable = false)
    private LocalDate periodEndDate;

    @Column(name = "statement_date", nullable = false)
    private LocalDate statementDate;

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
    public static class StatementLine {
        private String lineId;
        private Integer lineNumber;
        private LocalDate transactionDate;
        private String description;
        private BigDecimal amount;
        private String referenceNumber;
        private List<String> matchedGLTransactionIds = new ArrayList<>();
        private String matchStatus;

        // Getters and setters
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

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        public String getReferenceNumber() {
            return referenceNumber;
        }

        public void setReferenceNumber(String referenceNumber) {
            this.referenceNumber = referenceNumber;
        }

        public List<String> getMatchedGLTransactionIds() {
            return matchedGLTransactionIds;
        }

        public void setMatchedGLTransactionIds(List<String> matchedGLTransactionIds) {
            this.matchedGLTransactionIds = matchedGLTransactionIds;
        }

        public String getMatchStatus() {
            return matchStatus;
        }

        public void setMatchStatus(String matchStatus) {
            this.matchStatus = matchStatus;
        }
    }

    public static class GLTransaction {
        private String glTransactionId;
        private String journalEntryId;
        private LocalDate transactionDate;
        private String description;
        private BigDecimal amount;
        private List<String> matchedStatementLineIds = new ArrayList<>();
        private String matchStatus;

        // Getters and setters
        public String getGlTransactionId() {
            return glTransactionId;
        }

        public void setGlTransactionId(String glTransactionId) {
            this.glTransactionId = glTransactionId;
        }

        public String getJournalEntryId() {
            return journalEntryId;
        }

        public void setJournalEntryId(String journalEntryId) {
            this.journalEntryId = journalEntryId;
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

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        public List<String> getMatchedStatementLineIds() {
            return matchedStatementLineIds;
        }

        public void setMatchedStatementLineIds(List<String> matchedStatementLineIds) {
            this.matchedStatementLineIds = matchedStatementLineIds;
        }

        public String getMatchStatus() {
            return matchStatus;
        }

        public void setMatchStatus(String matchStatus) {
            this.matchStatus = matchStatus;
        }
    }

    public static class Adjustment {
        private String adjustmentId;
        private String journalEntryId;
        private String adjustmentType;
        private BigDecimal amount;
        private String description;

        // Getters and setters
        public String getAdjustmentId() {
            return adjustmentId;
        }

        public void setAdjustmentId(String adjustmentId) {
            this.adjustmentId = adjustmentId;
        }

        public String getJournalEntryId() {
            return journalEntryId;
        }

        public void setJournalEntryId(String journalEntryId) {
            this.journalEntryId = journalEntryId;
        }

        public String getAdjustmentType() {
            return adjustmentType;
        }

        public void setAdjustmentType(String adjustmentType) {
            this.adjustmentType = adjustmentType;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    // Constructors
    public Reconciliation() {
    }

    public Reconciliation(String reconciliationId) {
        this.reconciliationId = reconciliationId;
    }

    // Getters and Setters
    public String getReconciliationId() {
        return reconciliationId;
    }

    public void setReconciliationId(String reconciliationId) {
        this.reconciliationId = reconciliationId;
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

    public LocalDate getPeriodStartDate() {
        return periodStartDate;
    }

    public void setPeriodStartDate(LocalDate periodStartDate) {
        this.periodStartDate = periodStartDate;
    }

    public LocalDate getPeriodEndDate() {
        return periodEndDate;
    }

    public void setPeriodEndDate(LocalDate periodEndDate) {
        this.periodEndDate = periodEndDate;
    }

    public LocalDate getStatementDate() {
        return statementDate;
    }

    public void setStatementDate(LocalDate statementDate) {
        this.statementDate = statementDate;
    }

    public BigDecimal getStatementEndingBalance() {
        return statementEndingBalance;
    }

    public void setStatementEndingBalance(BigDecimal statementEndingBalance) {
        this.statementEndingBalance = statementEndingBalance;
    }

    public BigDecimal getGlEndingBalance() {
        return glEndingBalance;
    }

    public void setGlEndingBalance(BigDecimal glEndingBalance) {
        this.glEndingBalance = glEndingBalance;
    }

    public BigDecimal getDifference() {
        return difference;
    }

    public void setDifference(BigDecimal difference) {
        this.difference = difference;
    }

    public ReconciliationStatus getStatus() {
        return status;
    }

    public void setStatus(ReconciliationStatus status) {
        this.status = status;
    }

    public List<StatementLine> getStatementLines() {
        return statementLines;
    }

    public void setStatementLines(List<StatementLine> statementLines) {
        this.statementLines = statementLines;
    }

    public List<GLTransaction> getGlTransactions() {
        return glTransactions;
    }

    public void setGlTransactions(List<GLTransaction> glTransactions) {
        this.glTransactions = glTransactions;
    }

    public List<Adjustment> getAdjustments() {
        return adjustments;
    }

    public void setAdjustments(List<Adjustment> adjustments) {
        this.adjustments = adjustments;
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

    public Instant getFinalizedAt() {
        return finalizedAt;
    }

    public void setFinalizedAt(Instant finalizedAt) {
        this.finalizedAt = finalizedAt;
    }

    public String getFinalizedBy() {
        return finalizedBy;
    }

    public void setFinalizedBy(String finalizedBy) {
        this.finalizedBy = finalizedBy;
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
