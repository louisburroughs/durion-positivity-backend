package com.positivity.accounting.internal.entity;

import jakarta.persistence.*;
import org.jspecify.annotations.NonNull;

import com.positivity.shared.id.UUIDv7Generator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Credit Memo entity for reversing invoice charges.
 * 
 * Credit Memos reduce Accounts Receivable by posting offsetting GL entries
 * that reverse revenue and tax, typically for returned goods, pricing errors,
 * or service credits.
 * 
 * Business Rules (from Issue #131):
 * - Must reference a finalized invoice
 * - Credit amount cannot exceed invoice outstanding balance
 * - Requires reason code for audit
 * - GL entries must be balanced
 * - Prior period adjustments posted to current period with flag
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/131">Issue
 *      #131</a>
 */
@Entity
@Table(name = "credit_memo", indexes = {
        @Index(name = "idx_credit_memo_original_invoice", columnList = "original_invoice_id"),
        @Index(name = "idx_credit_memo_customer", columnList = "customer_id"),
        @Index(name = "idx_credit_memo_status", columnList = "status"),
        @Index(name = "idx_credit_memo_posted_timestamp", columnList = "posted_timestamp")
})
public class CreditMemo {

    @Id
    @Column(name = "credit_memo_id", nullable = false, updatable = false)
    private UUID creditMemoId;

    @Column(name = "original_invoice_id", nullable = false, updatable = false)
    private UUID originalInvoiceId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "credit_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal creditAmount;

    @Column(name = "tax_amount_reversed", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmountReversed;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "reason_code", nullable = false, length = 50)
    private String reasonCode;

    @Column(name = "justification_note", length = 1000)
    private String justificationNote;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CreditMemoStatus status;

    @Column(name = "creation_timestamp", nullable = false, updatable = false)
    private Instant creationTimestamp;

    @Column(name = "posted_timestamp")
    private Instant postedTimestamp;

    @Column(name = "created_by_user_id", nullable = false, length = 50)
    private String createdByUserId;

    @Column(name = "prior_period_adjustment", nullable = false)
    private Boolean priorPeriodAdjustment = false;

    @Column(name = "original_period_id", length = 50)
    private String originalPeriodId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @PrePersist
    protected void onCreate() {
        if (creditMemoId == null) {
            creditMemoId = UUIDv7Generator.generate();
        }
        if (creationTimestamp == null) {
            creationTimestamp = Instant.now();
        }
        if (status == CreditMemoStatus.POSTED && postedTimestamp == null) {
            postedTimestamp = Instant.now();
        }
    }

    // Getters and Setters

    public UUID getCreditMemoId() {
        return creditMemoId;
    }

    public void setCreditMemoId(@NonNull UUID creditMemoId) {
        this.creditMemoId = creditMemoId;
    }

    public UUID getOriginalInvoiceId() {
        return originalInvoiceId;
    }

    public void setOriginalInvoiceId(@NonNull UUID originalInvoiceId) {
        this.originalInvoiceId = originalInvoiceId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(@NonNull UUID customerId) {
        this.customerId = customerId;
    }

    public BigDecimal getCreditAmount() {
        return creditAmount;
    }

    public void setCreditAmount(@NonNull BigDecimal creditAmount) {
        this.creditAmount = creditAmount;
    }

    public BigDecimal getTaxAmountReversed() {
        return taxAmountReversed;
    }

    public void setTaxAmountReversed(@NonNull BigDecimal taxAmountReversed) {
        this.taxAmountReversed = taxAmountReversed;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(@NonNull BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(@NonNull String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getJustificationNote() {
        return justificationNote;
    }

    public void setJustificationNote(String justificationNote) {
        this.justificationNote = justificationNote;
    }

    public CreditMemoStatus getStatus() {
        return status;
    }

    public void setStatus(@NonNull CreditMemoStatus status) {
        this.status = status;
    }

    public Instant getCreationTimestamp() {
        return creationTimestamp;
    }

    public void setCreationTimestamp(@NonNull Instant creationTimestamp) {
        this.creationTimestamp = creationTimestamp;
    }

    public Instant getPostedTimestamp() {
        return postedTimestamp;
    }

    public void setPostedTimestamp(Instant postedTimestamp) {
        this.postedTimestamp = postedTimestamp;
    }

    public String getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(@NonNull String createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public Boolean getPriorPeriodAdjustment() {
        return priorPeriodAdjustment;
    }

    public void setPriorPeriodAdjustment(@NonNull Boolean priorPeriodAdjustment) {
        this.priorPeriodAdjustment = priorPeriodAdjustment;
    }

    public String getOriginalPeriodId() {
        return originalPeriodId;
    }

    public void setOriginalPeriodId(String originalPeriodId) {
        this.originalPeriodId = originalPeriodId;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(@NonNull String currency) {
        this.currency = currency;
    }
}
