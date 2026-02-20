package com.positivity.invoice.internal.entity;

import com.positivity.invoice.internal.enums.InvoiceDeliveryMethod;
import com.positivity.invoice.internal.enums.InvoiceGroupingStrategy;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Billing rules configuration for commercial accounts.
 * System of record: pos-invoice
 * CAP:092 - Preferences & Billing Rules
 */
@Entity
@Table(name = "billing_rules", indexes = {
        @Index(name = "idx_billing_rules_party_id", columnList = "party_id", unique = true)
})
public class BillingRules {

    @Id
    @Column(name = "id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "party_id", nullable = false, unique = true, length = 36)
    private String partyId;

    @Column(name = "purchase_order_required", nullable = false)
    private boolean purchaseOrderRequired = false;

    @Column(name = "payment_terms_code", nullable = false, length = 50)
    private String paymentTermsCode = "NET_30";

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_delivery_method", nullable = false, length = 20)
    private InvoiceDeliveryMethod invoiceDeliveryMethod = InvoiceDeliveryMethod.EMAIL;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_grouping_strategy", nullable = false, length = 30)
    private InvoiceGroupingStrategy invoiceGroupingStrategy = InvoiceGroupingStrategy.PER_WORKORDER;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 36)
    private String updatedBy;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }

        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and Setters

    @Nullable
    public UUID getId() {
        return id;
    }

    public void setId(@Nullable UUID id) {
        this.id = id;
    }

    @NonNull
    public String getPartyId() {
        return partyId;
    }

    public void setPartyId(@NonNull String partyId) {
        this.partyId = partyId;
    }

    public boolean isPurchaseOrderRequired() {
        return purchaseOrderRequired;
    }

    public void setPurchaseOrderRequired(boolean purchaseOrderRequired) {
        this.purchaseOrderRequired = purchaseOrderRequired;
    }

    @NonNull
    public String getPaymentTermsCode() {
        return paymentTermsCode;
    }

    public void setPaymentTermsCode(@NonNull String paymentTermsCode) {
        this.paymentTermsCode = paymentTermsCode;
    }

    @NonNull
    public InvoiceDeliveryMethod getInvoiceDeliveryMethod() {
        return invoiceDeliveryMethod;
    }

    public void setInvoiceDeliveryMethod(@NonNull InvoiceDeliveryMethod invoiceDeliveryMethod) {
        this.invoiceDeliveryMethod = invoiceDeliveryMethod;
    }

    @NonNull
    public InvoiceGroupingStrategy getInvoiceGroupingStrategy() {
        return invoiceGroupingStrategy;
    }

    public void setInvoiceGroupingStrategy(@NonNull InvoiceGroupingStrategy invoiceGroupingStrategy) {
        this.invoiceGroupingStrategy = invoiceGroupingStrategy;
    }

    @NonNull
    public Integer getVersion() {
        return version;
    }

    public void setVersion(@NonNull Integer version) {
        this.version = version;
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

    @NonNull
    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(@NonNull String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
