package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.invoice.internal.enums.InvoiceDeliveryMethod;
import com.positivity.invoice.internal.enums.InvoiceGroupingStrategy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * DTO for billing rules responses.
 * CAP:092 - Preferences & Billing Rules
 */
@Schema(description = "Billing rules and invoicing preferences for a billing party")
public class BillingRulesDTO {
    @Nullable
    @Schema(
            description = "Unique identifier of the billing rules record",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID id;

    @NonNull
    @NotNull
    @Schema(
            description = "Identifier of the party these billing rules apply to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    private String partyId;

    @Schema(
            description = "Whether a purchase order is required before invoicing",
            example = "true",
            requiredMode = REQUIRED)
    private boolean purchaseOrderRequired;

    @NonNull
    @NotNull
    @Schema(description = "Payment terms code governing due dates", example = "NET30", requiredMode = REQUIRED)
    private String paymentTermsCode;

    @NonNull
    @NotNull
    @Schema(description = "Preferred method for delivering invoices", example = "EMAIL", requiredMode = REQUIRED)
    private InvoiceDeliveryMethod invoiceDeliveryMethod;

    @NonNull
    @NotNull
    @Schema(
            description = "Strategy for grouping line items into invoices",
            example = "PER_WORKORDER",
            requiredMode = REQUIRED)
    private InvoiceGroupingStrategy invoiceGroupingStrategy;

    @NonNull
    @NotNull
    @Schema(description = "Optimistic-locking version of the record", example = "3", requiredMode = REQUIRED)
    private Integer version;

    @NonNull
    @NotNull
    @Schema(
            description = "Timestamp when the record was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    private Instant createdAt;

    @NonNull
    @NotNull
    @Schema(
            description = "Timestamp when the record was last updated",
            example = "2026-01-16T11:45:00Z",
            requiredMode = REQUIRED)
    private Instant updatedAt;

    @NonNull
    @NotNull
    @Schema(
            description = "Identifier of the actor who last updated the record",
            example = "jdoe",
            requiredMode = REQUIRED)
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
