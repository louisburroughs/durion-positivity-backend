package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shared.enums.InvoiceDeliveryMethod;
import com.positivity.shared.enums.InvoiceGroupingStrategy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * DTO for billing rules responses.
 * CAP:092 - Preferences & Billing Rules
 */
@Schema(description = "Billing rules payload associated to a party")
public class BillingRulesDTO {
    @Nullable
    @Schema(
            description = "Billing rule identifier",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = NOT_REQUIRED)
    private UUID id;

    @NotBlank(message = "partyId is required")
    @NonNull
    @Schema(description = "Party identifier", example = "CUST-12345", requiredMode = REQUIRED)
    private String partyId;

    @Schema(description = "Whether purchase order number is required", example = "true", requiredMode = REQUIRED)
    private boolean purchaseOrderRequired;

    @NotBlank(message = "paymentTermsCode is required")
    @NonNull
    @Schema(description = "Payment terms code", example = "NET30", requiredMode = REQUIRED)
    private String paymentTermsCode;

    @NotNull(message = "invoiceDeliveryMethod is required")
    @NonNull
    @Schema(description = "Invoice delivery method", example = "EMAIL", requiredMode = REQUIRED)
    private InvoiceDeliveryMethod invoiceDeliveryMethod;

    @NotNull(message = "invoiceGroupingStrategy is required")
    @NonNull
    @Schema(description = "Invoice grouping strategy", example = "PER_WORKORDER", requiredMode = REQUIRED)
    private InvoiceGroupingStrategy invoiceGroupingStrategy;

    @NotNull(message = "version is required")
    @NonNull
    @Schema(description = "Optimistic lock version", example = "1", requiredMode = REQUIRED)
    private Integer version;

    @NotNull(message = "createdAt is required")
    @NonNull
    @Schema(description = "Creation timestamp", example = "2026-03-02T10:15:30Z", requiredMode = REQUIRED)
    private Instant createdAt;

    @NotNull(message = "updatedAt is required")
    @NonNull
    @Schema(description = "Last update timestamp", example = "2026-03-02T11:00:00Z", requiredMode = REQUIRED)
    private Instant updatedAt;

    @NotBlank(message = "updatedBy is required")
    @NonNull
    @Schema(
            description = "Actor identifier that last updated billing rules",
            example = "advisor@shop.local",
            requiredMode = REQUIRED)
    private String updatedBy;

    // Constructors

    // Mandatory no-arg constructor for Jackson deserialization; the @NonNull fields are
    // populated from the request body and their required-ness is enforced by the jakarta
    // @NotBlank/@NotNull validation on each field, not by this constructor.
    @SuppressWarnings("java:S2637")
    public BillingRulesDTO() {}

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
