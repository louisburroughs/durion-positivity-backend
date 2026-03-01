package com.positivity.invoice.internal.entity;

import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_number", length = 64)
    private String invoiceNumber;

    @Column(name = "workorder_id", columnDefinition = "UUID", nullable = false)
    private UUID workorderId;

    @Column(name = "estimate_id", columnDefinition = "UUID")
    private UUID estimateId;

    @Column(name = "approval_id", columnDefinition = "UUID")
    private UUID approvalId;

    @Column(name = "customer_id", length = 64)
    private String partyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(name = "subtotal", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal tax = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "adjustments_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal adjustmentsAmount = BigDecimal.ZERO;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "finalized_by", length = 64)
    private String finalizedBy;

    // -- Story #13 scaffold columns (nullable, no migration added yet) --
    @Column(name = "gl_entry_id", columnDefinition = "UUID", nullable = true)
    private UUID glEntryId;

    @Column(name = "reverted_at", nullable = true)
    private Instant revertedAt;

    @Column(name = "reversion_reason", length = 512, nullable = true)
    private String reversionReason;

    @Column(name = "reverted_by", length = 64, nullable = true)
    private String revertedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvoiceItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvoiceAdjustment> adjustmentEntries = new ArrayList<>();

    @Transient
    private BigDecimal adjustments = BigDecimal.ZERO;

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = InvoiceStatus.DRAFT;
        }
        if (subtotal == null) {
            subtotal = BigDecimal.ZERO;
        }
        if (tax == null) {
            tax = BigDecimal.ZERO;
        }
        if (total == null) {
            total = BigDecimal.ZERO;
        }
        if (adjustmentsAmount == null) {
            adjustmentsAmount = BigDecimal.ZERO;
        }
        if (adjustments == null) {
            adjustments = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    public void preUpdate() {
        if (adjustments == null) {
            adjustments = BigDecimal.ZERO;
        }
        adjustmentsAmount = adjustments;
        if (tax == null) {
            tax = BigDecimal.ZERO;
        }
        if (total == null) {
            total = BigDecimal.ZERO;
        }
    }

    public void addItem(@NonNull InvoiceItem item) {
        items.add(item);
        item.setInvoice(this);
    }

    public void addAdjustment(@NonNull InvoiceAdjustment adjustment) {
        adjustmentEntries.add(adjustment);
        adjustment.setInvoice(this);
    }

    @Nullable
    public UUID getId() {
        return id;
    }

    public void setId(@Nullable UUID id) {
        this.id = id;
    }

    @Nullable
    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(@Nullable String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    @NonNull
    public UUID getWorkorderId() {
        return workorderId;
    }

    public void setWorkorderId(@NonNull UUID workorderId) {
        this.workorderId = workorderId;
    }

    @Nullable
    public UUID getEstimateId() {
        return estimateId;
    }

    public void setEstimateId(@Nullable UUID estimateId) {
        this.estimateId = estimateId;
    }

    @Nullable
    public UUID getApprovalId() {
        return approvalId;
    }

    public void setApprovalId(@Nullable UUID approvalId) {
        this.approvalId = approvalId;
    }

    @Nullable
    public String getPartyId() {
        return partyId;
    }

    public void setPartyId(@Nullable String partyId) {
        this.partyId = partyId;
    }

    @NonNull
    public InvoiceStatus getStatus() {
        return status;
    }

    public void setStatus(@NonNull InvoiceStatus status) {
        this.status = status;
    }

    @NonNull
    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(@NonNull BigDecimal subtotal) {
        this.subtotal = subtotal;
    }

    @NonNull
    public BigDecimal getTax() {
        return tax;
    }

    public void setTax(@NonNull BigDecimal tax) {
        this.tax = tax;
    }

    @NonNull
    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(@NonNull BigDecimal total) {
        this.total = total;
    }

    @NonNull
    public BigDecimal getAdjustmentsAmount() {
        return adjustmentsAmount;
    }

    public void setAdjustmentsAmount(@NonNull BigDecimal adjustmentsAmount) {
        this.adjustmentsAmount = adjustmentsAmount;
    }

    @NonNull
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(@NonNull Instant createdAt) {
        this.createdAt = createdAt;
    }

    @NonNull
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(@NonNull Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Nullable
    public Instant getFinalizedAt() {
        return finalizedAt;
    }

    public void setFinalizedAt(@Nullable Instant finalizedAt) {
        this.finalizedAt = finalizedAt;
    }

    @Nullable
    public String getFinalizedBy() {
        return finalizedBy;
    }

    public void setFinalizedBy(@Nullable String finalizedBy) {
        this.finalizedBy = finalizedBy;
    }

    @Nullable
    public UUID getGlEntryId() {
        return glEntryId;
    }

    public void setGlEntryId(@Nullable UUID glEntryId) {
        this.glEntryId = glEntryId;
    }

    @Nullable
    public Instant getRevertedAt() {
        return revertedAt;
    }

    public void setRevertedAt(@Nullable Instant revertedAt) {
        this.revertedAt = revertedAt;
    }

    @Nullable
    public String getReversionReason() {
        return reversionReason;
    }

    public void setReversionReason(@Nullable String reversionReason) {
        this.reversionReason = reversionReason;
    }

    @Nullable
    public String getRevertedBy() {
        return revertedBy;
    }

    public void setRevertedBy(@Nullable String revertedBy) {
        this.revertedBy = revertedBy;
    }

    @NonNull
    public Integer getVersion() {
        return version;
    }

    public void setVersion(@NonNull Integer version) {
        this.version = version;
    }

    @NonNull
    public List<InvoiceItem> getItems() {
        return items;
    }

    public void setItems(@NonNull List<InvoiceItem> items) {
        this.items = items;
    }

    @NonNull
    public List<InvoiceAdjustment> getAdjustmentEntries() {
        return adjustmentEntries;
    }

    public void setAdjustmentEntries(@NonNull List<InvoiceAdjustment> adjustments) {
        this.adjustmentEntries = adjustments;
    }

    @NonNull
    public BigDecimal getAdjustments() {
        return adjustments;
    }

    public void setAdjustments(@NonNull BigDecimal adjustments) {
        this.adjustments = adjustments;
    }
}
