package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.shared.id.UUIDv7Id;
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
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
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
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"journalEntry"})
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "vendor_bill",
        indexes = {
            @Index(name = "idx_vendor_bill_vendor", columnList = "vendor_id"),
            @Index(name = "idx_vendor_bill_status", columnList = "status"),
            @Index(name = "idx_vendor_bill_number", columnList = "bill_number"),
            @Index(name = "idx_vendor_bill_due_date", columnList = "due_date"),
            @Index(name = "idx_vendor_bill_origin_event", columnList = "origin_event_id"),
            @Index(name = "idx_vendor_bill_po", columnList = "purchase_order_id")
        })
public class VendorBill {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "vendor_bill_id", nullable = false, columnDefinition = "UUID")
    private UUID vendorBillId;

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    @Column(name = "vendor_name", length = 200)
    private String vendorName;

    @Column(name = "bill_number", length = 50, nullable = false)
    private String billNumber;

    @Column(name = "bill_date", nullable = false)
    private LocalDateTime billDate;

    @Column(name = "due_date")
    private LocalDateTime dueDate;

    @Column(name = "total_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private VendorBillStatus status = VendorBillStatus.PENDING_RECEIPT_MATCH;

    // Purchase Order reference (for three-way matching)
    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    @Column(name = "purchase_order_number", length = 50)
    private String purchaseOrderNumber;

    // Traceability
    @Column(name = "origin_event_id")
    private UUID originEventId;

    @Column(name = "origin_event_type", length = 100)
    private String originEventType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_entry_id")
    private JournalEntry journalEntry;

    @Column(name = "payment_transaction_id")
    private UUID paymentTransactionId;

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

    public VendorBill(UUID vendorBillId) {
        this.vendorBillId = vendorBillId;
    }

    // Scalar compatibility accessors for journalEntryId
    @Transient
    public UUID getJournalEntryId() {
        return journalEntry != null ? journalEntry.getJournalEntryId() : null;
    }

    public void setJournalEntryId(UUID journalEntryId) {
        this.journalEntry = journalEntryId != null ? new JournalEntry(journalEntryId) : null;
    }
}
