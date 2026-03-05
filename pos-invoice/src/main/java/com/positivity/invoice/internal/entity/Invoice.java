package com.positivity.invoice.internal.entity;

import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import org.jspecify.annotations.NonNull;
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
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
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

    // Pending Flyway migration: keep these fields transient until DB columns exist.
    @Transient
    private UUID glEntryId;

    @Transient
    private Instant revertedAt;

    @Transient
    private String reversionReason;

    @Transient
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

}