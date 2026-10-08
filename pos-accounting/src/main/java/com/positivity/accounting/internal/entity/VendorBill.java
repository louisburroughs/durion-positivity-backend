package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillDifferenceClass;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
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
import lombok.AccessLevel;
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
 * Lifecycle (#2509): PENDING_RECEIPT_MATCH | MATCH_EXCEPTION → AWAITING_APPROVAL → APPROVED | REJECTED; an
 * APPROVED bill is posted in the approval's transaction and may be VOIDED while nothing is allocated (AW37, AW42).
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
public class VendorBill extends TenantScopedEntity {

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

    @Setter(AccessLevel.NONE)
    @Column(name = "bill_number", length = 50, nullable = false)
    private String billNumber;

    /**
     * {@link VendorBillNumbers#normalise} of {@link #billNumber}: the stored half of the duplicate
     * rule (#2501, ADR-0070 Decision 4). Written only by {@link #setBillNumber}, so no writer of a
     * bill number can leave it stale; the partial unique index {@code uq_vendor_bill_duplicate_rule}
     * reads it.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "bill_number_key", length = VendorBillNumbers.MAX_KEY_LENGTH, nullable = false)
    private String billNumberKey;

    @Column(name = "bill_date", nullable = false)
    private LocalDateTime billDate;

    @Column(name = "due_date")
    private LocalDateTime dueDate;

    @Column(name = "total_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalAmount;

    /**
     * ISO 4217 currency the bill is stated in, as the vendor's document states it (ADR-0067 DF-1,
     * #2309). Null on bills that predate the column or come from a source that states none, which
     * means the ledger currency (ADR-0067 E-3).
     */
    @Column(name = "currency", length = 3)
    private String currency;

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

    /**
     * The net the vendor's document states (EDI, AW39), signed like {@link #totalAmount}; null when the source
     * states no header amounts (a goods-receipt bill posts from its lines).
     */
    @Column(name = "net_amount", precision = 19, scale = 4)
    private BigDecimal netAmount;

    /**
     * The tax the vendor's document states, never recalculated (AW39); with only the net stated, gross - net; with
     * neither, zero (AW47). Null on a bill whose source states no header amounts.
     */
    @Column(name = "tax_amount", precision = 19, scale = 4)
    private BigDecimal taxAmount;

    /** Lines the vendor's document states (EDI), at least 1: the rounding tolerance's base (AW47). */
    @Column(name = "stated_line_count")
    private Integer statedLineCount;

    /** How an unreconciled gross - (net + tax) posts, as proposed at submission (AW47). */
    @Enumerated(EnumType.STRING)
    @Column(name = "difference_class", length = 30)
    private VendorBillDifferenceClass differenceClass;

    @Column(name = "difference_expense_mapping_key", length = 100)
    private String differenceExpenseMappingKey;

    @Column(name = "difference_justification", length = 1000)
    private String differenceJustification;

    // Status transition audit
    /** When the bill was sent for approval (#2509): by a person, a HIGH match or a candidate selection. */
    @Column(name = "submitted_at")
    private Instant submittedAt;

    /** Who sent it: the caller from the security context, or {@code SYSTEM} for a HIGH match. */
    @Column(name = "submitted_by", length = 50)
    private String submittedBy;

    @Column(name = "submission_justification", length = 1000)
    private String submissionJustification;

    /** The class proposed at submission (AW39); the approver's own classification wins. */
    @Enumerated(EnumType.STRING)
    @Column(name = "proposed_debit_class", length = 30)
    private VendorBillDebitClass proposedDebitClass;

    /** The {@code VENDOR_BILL} expense key proposed at submission. */
    @Column(name = "proposed_expense_mapping_key", length = 100)
    private String proposedExpenseMappingKey;

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

    /**
     * Sets the bill number and, with it, the duplicate-rule key.
     *
     * @param billNumber the number as the vendor or the generator wrote it
     */
    public void setBillNumber(String billNumber) {
        this.billNumber = billNumber;
        this.billNumberKey = billNumber == null ? null : VendorBillNumbers.normalise(billNumber);
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
