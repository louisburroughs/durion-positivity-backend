package com.positivity.invoice.internal.entity;

import com.positivity.invoice.internal.enums.InvoiceAdjustmentType;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "invoice_adjustments")
public class InvoiceAdjustment {

    @Id
    @Column(name = "id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private InvoiceAdjustmentType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 512)
    private String reason;

    @Column(name = "authorized_by", nullable = false, length = 64)
    private String authorizedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }

    @Nullable
    public UUID getId() {
        return id;
    }

    public void setId(@Nullable UUID id) {
        this.id = id;
    }

    @Nullable
    public Invoice getInvoice() {
        return invoice;
    }

    public void setInvoice(@NonNull Invoice invoice) {
        this.invoice = invoice;
    }

    @NonNull
    public InvoiceAdjustmentType getType() {
        return type;
    }

    public void setType(@NonNull InvoiceAdjustmentType type) {
        this.type = type;
    }

    @NonNull
    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(@NonNull BigDecimal amount) {
        this.amount = amount;
    }

    @NonNull
    public String getReason() {
        return reason;
    }

    public void setReason(@NonNull String reason) {
        this.reason = reason;
    }

    @NonNull
    public String getAuthorizedBy() {
        return authorizedBy;
    }

    public void setAuthorizedBy(@NonNull String authorizedBy) {
        this.authorizedBy = authorizedBy;
    }

    @NonNull
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(@NonNull Instant createdAt) {
        this.createdAt = createdAt;
    }
}
