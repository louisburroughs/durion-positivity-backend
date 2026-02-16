package com.positivity.invoice.internal.entity;

import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
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
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(name = "subtotal", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "finalized_by", length = 64)
    private String finalizedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvoiceItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvoiceAdjustment> adjustments = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = InvoiceStatus.DRAFT;
        }
        if (subtotal == null) {
            subtotal = BigDecimal.ZERO;
        }
        if (taxAmount == null) {
            taxAmount = BigDecimal.ZERO;
        }
        if (totalAmount == null) {
            totalAmount = BigDecimal.ZERO;
        }
    }

    public void addItem(@NonNull InvoiceItem item) {
        items.add(item);
        item.setInvoice(this);
    }

    public void addAdjustment(@NonNull InvoiceAdjustment adjustment) {
        adjustments.add(adjustment);
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
    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(@Nullable String customerId) {
        this.customerId = customerId;
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
    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(@NonNull BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    @NonNull
    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(@NonNull BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    @NonNull
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(@NonNull Instant createdAt) {
        this.createdAt = createdAt;
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
    public List<InvoiceAdjustment> getAdjustments() {
        return adjustments;
    }

    public void setAdjustments(@NonNull List<InvoiceAdjustment> adjustments) {
        this.adjustments = adjustments;
    }
}
