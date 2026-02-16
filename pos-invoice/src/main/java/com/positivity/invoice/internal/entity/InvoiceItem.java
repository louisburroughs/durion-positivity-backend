package com.positivity.invoice.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "invoice_items")
public class InvoiceItem {

    @Id
    @Column(name = "id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "invoice_id", columnDefinition = "UUID", nullable = false, insertable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "description", nullable = false, length = 512)
    private String description;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotal;

    @Column(name = "workorder_item_id", columnDefinition = "UUID")
    private UUID workorderItemId;

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

    @Nullable
    public UUID getInvoiceId() {
        return invoiceId;
    }

    @NonNull
    public String getDescription() {
        return description;
    }

    public void setDescription(@NonNull String description) {
        this.description = description;
    }

    @NonNull
    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(@NonNull BigDecimal quantity) {
        this.quantity = quantity;
    }

    @NonNull
    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(@NonNull BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    @NonNull
    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public void setLineTotal(@NonNull BigDecimal lineTotal) {
        this.lineTotal = lineTotal;
    }

    @Nullable
    public UUID getWorkorderItemId() {
        return workorderItemId;
    }

    public void setWorkorderItemId(@Nullable UUID workorderItemId) {
        this.workorderItemId = workorderItemId;
    }
}
