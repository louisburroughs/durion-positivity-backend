package com.positivity.accounting.entity;

import com.positivity.accounting.enums.VendorBillStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Vendor Bill (Accounts Payable) entity.
 * 
 * Lifecycle: PENDING_REVIEW → APPROVED → PAID (or REJECTED/CANCELLED)
 * 
 * Traceability: originEventId → vendorBill → journalEntryId →
 * paymentTransactionId
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Vendor Bill</a>
 */
@Entity
@Table(name = "vendor_bill", indexes = {
        @Index(name = "idx_vendor_bill_vendor", columnList = "vendor_id"),
        @Index(name = "idx_vendor_bill_status", columnList = "status"),
        @Index(name = "idx_vendor_bill_number", columnList = "bill_number"),
        @Index(name = "idx_vendor_bill_due_date", columnList = "due_date"),
        @Index(name = "idx_vendor_bill_origin_event", columnList = "origin_event_id")
})
public class VendorBill {

    @Id
    @Column(name = "vendor_bill_id", length = 50, nullable = false)
    private String vendorBillId;

    @Column(name = "vendor_id", length = 50, nullable = false)
    private String vendorId;

    @Column(name = "vendor_name", length = 200)
    private String vendorName;

    @Column(name = "bill_number", length = 50, nullable = false)
    private String billNumber;

    @Column(name = "bill_date", nullable = false)
    private LocalDate billDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "total_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private VendorBillStatus status = VendorBillStatus.PENDING_REVIEW;

    // Traceability
    @Column(name = "origin_event_id", length = 50)
    private String originEventId;

    @Column(name = "origin_event_type", length = 100)
    private String originEventType;

    @Column(name = "journal_entry_id", length = 50)
    private String journalEntryId;

    @Column(name = "payment_transaction_id", length = 50)
    private String paymentTransactionId;

    // Audit fields
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "modified_at", nullable = false)
    private Instant modifiedAt;

    @Column(name = "modified_by", length = 50, nullable = false)
    private String modifiedBy;

    // Status transition audit
    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by", length = 50)
    private String approvedBy;

    @Column(name = "approval_justification", length = 1000)
    private String approvalJustification;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejected_by", length = 50)
    private String rejectedBy;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "paid_by", length = 50)
    private String paidBy;

    // Constructors
    public VendorBill() {
    }

    // Getters and Setters
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

    public String getBillNumber() {
        return billNumber;
    }

    public void setBillNumber(String billNumber) {
        this.billNumber = billNumber;
    }

    public LocalDate getBillDate() {
        return billDate;
    }

    public void setBillDate(LocalDate billDate) {
        this.billDate = billDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public VendorBillStatus getStatus() {
        return status;
    }

    public void setStatus(VendorBillStatus status) {
        this.status = status;
    }

    public String getOriginEventId() {
        return originEventId;
    }

    public void setOriginEventId(String originEventId) {
        this.originEventId = originEventId;
    }

    public String getOriginEventType() {
        return originEventType;
    }

    public void setOriginEventType(String originEventType) {
        this.originEventType = originEventType;
    }

    public String getJournalEntryId() {
        return journalEntryId;
    }

    public void setJournalEntryId(String journalEntryId) {
        this.journalEntryId = journalEntryId;
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

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public void setRejectedAt(Instant rejectedAt) {
        this.rejectedAt = rejectedAt;
    }

    public String getRejectedBy() {
        return rejectedBy;
    }

    public void setRejectedBy(String rejectedBy) {
        this.rejectedBy = rejectedBy;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }

    public String getPaidBy() {
        return paidBy;
    }

    public void setPaidBy(String paidBy) {
        this.paidBy = paidBy;
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
}
