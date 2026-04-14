package com.positivity.invoice.internal.dto;

import com.positivity.invoice.internal.enums.InvoiceDeliveryMethod;
import com.positivity.invoice.internal.enums.InvoiceGroupingStrategy;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * DTO for billing rules responses.
 * CAP:092 - Preferences & Billing Rules
 */
public class BillingRulesDTO {
    @Nullable
    private UUID id;

    @NonNull
    private String partyId;

    private boolean purchaseOrderRequired;

    @NonNull
    private String paymentTermsCode;

    @NonNull
    private InvoiceDeliveryMethod invoiceDeliveryMethod;

    @NonNull
    private InvoiceGroupingStrategy invoiceGroupingStrategy;

    @NonNull
    private Integer version;

    @NonNull
    private Instant createdAt;

    @NonNull
    private Instant updatedAt;

    @NonNull
    private String updatedBy;

    // Constructors

    public BillingRulesDTO() {}

    public BillingRulesDTO(
            @NonNull String partyId,
            boolean purchaseOrderRequired,
            @NonNull String paymentTermsCode,
            @NonNull InvoiceDeliveryMethod invoiceDeliveryMethod,
            @NonNull InvoiceGroupingStrategy invoiceGroupingStrategy) {
        this.partyId = partyId;
        this.purchaseOrderRequired = purchaseOrderRequired;
        this.paymentTermsCode = paymentTermsCode;
        this.invoiceDeliveryMethod = invoiceDeliveryMethod;
        this.invoiceGroupingStrategy = invoiceGroupingStrategy;
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
