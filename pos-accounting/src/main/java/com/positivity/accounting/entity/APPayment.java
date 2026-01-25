package com.positivity.accounting.entity;

import com.positivity.accounting.enums.APPaymentStatus;
import com.positivity.accounting.enums.PaymentMethod;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * AP Payment entity - manages payment workflow for vendor bills.
 * 
 * Lifecycle: PENDING_APPROVAL → APPROVED → SCHEDULED → PAID (or CANCELLED)
 * 
 * Approval threshold logic:
 * - amount < threshold: requires accounting:ap_payment:approve_under_threshold
 * - amount >= threshold: requires accounting:ap_payment:approve_over_threshold
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - AP Payment</a>
 */
@Entity
@Table(name = "ap_payment", indexes = {
        @Index(name = "idx_ap_payment_vendor_bill", columnList = "vendor_bill_id"),
        @Index(name = "idx_ap_payment_vendor", columnList = "vendor_id"),
        @Index(name = "idx_ap_payment_status", columnList = "status"),
        @Index(name = "idx_ap_payment_date", columnList = "payment_date")
})
public class APPayment {

    @Id
    @Column(name = "payment_id", length = 50, nullable = false)
    private String paymentId;

    @Column(name = "vendor_bill_id", length = 50, nullable = false)
    private String vendorBillId;

    @Column(name = "vendor_id", length = 50, nullable = false)
    private String vendorId;

    @Column(name = "vendor_name", length = 200)
    private String vendorName;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private APPaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "bank_account_id", length = 50)
    private String bankAccountId;

    @Column(name = "approval_level", length = 30)
    private String approvalLevel;

    @Column(name = "approval_threshold", precision = 19, scale = 4)
    private BigDecimal approvalThreshold;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by", length = 50)
    private String approvedBy;

    @Column(name = "approval_justification", length = 1000)
    private String approvalJustification;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "scheduled_by", length = 50)
    private String scheduledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by", length = 50)
    private String cancelledBy;

    @Column(name = "cancellation_reason", length = 1000)
    private String cancellationReason;

    @Column(name = "payment_transaction_id", length = 50)
    private String paymentTransactionId;

    // Audit fields
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    // Constructors
    public APPayment() {
    }

    public APPayment(String paymentId) {
        this.paymentId = paymentId;
    }

    // Getters and Setters
    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public String getVendorBillId() {
        return vendorBillId;
    }

    public void setVendorBillId(String vendorBillId) {
        this.vendorBillId = vendorBillId;
    }

    public String getVendorId() {
        return vendorId;
    }

    public void setVendorId(String vendorId) {
        this.vendorId = vendorId;
    }

    public String getVendorName() {
        return vendorName;
    }

    public void setVendorName(String vendorName) {
        this.vendorName = vendorName;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public APPaymentStatus getStatus() {
        return status;
    }

    public void setStatus(APPaymentStatus status) {
        this.status = status;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(LocalDate paymentDate) {
        this.paymentDate = paymentDate;
    }

    public String getBankAccountId() {
        return bankAccountId;
    }

    public void setBankAccountId(String bankAccountId) {
        this.bankAccountId = bankAccountId;
    }

    public String getApprovalLevel() {
        return approvalLevel;
    }

    public void setApprovalLevel(String approvalLevel) {
        this.approvalLevel = approvalLevel;
    }

    public BigDecimal getApprovalThreshold() {
        return approvalThreshold;
    }

    public void setApprovalThreshold(BigDecimal approvalThreshold) {
        this.approvalThreshold = approvalThreshold;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public String getApprovalJustification() {
        return approvalJustification;
    }

    public void setApprovalJustification(String approvalJustification) {
        this.approvalJustification = approvalJustification;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public String getScheduledBy() {
        return scheduledBy;
    }

    public void setScheduledBy(String scheduledBy) {
        this.scheduledBy = scheduledBy;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public String getCancelledBy() {
        return cancelledBy;
    }

    public void setCancelledBy(String cancelledBy) {
        this.cancelledBy = cancelledBy;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public void setCancellationReason(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }

    public String getPaymentTransactionId() {
        return paymentTransactionId;
    }

    public void setPaymentTransactionId(String paymentTransactionId) {
        this.paymentTransactionId = paymentTransactionId;
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

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (status == null) {
            status = APPaymentStatus.PENDING_APPROVAL;
        }
        if (currency == null) {
            currency = "USD";
        }
    }
}
