package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.positivity.invoice.internal.enums.InvoiceAdjustmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "A monetary adjustment applied to an invoice")
public class InvoiceAdjustmentResponse {

    @Schema(
            description = "Unique identifier of the adjustment entry",
            example = "01960003-0000-7000-8000-000000000030",
            requiredMode = NOT_REQUIRED)
    private UUID id;

    @Schema(description = "Type of adjustment applied", example = "DISCOUNT", requiredMode = NOT_REQUIRED)
    private InvoiceAdjustmentType type;

    @Schema(description = "Adjustment amount", example = "25.00", requiredMode = NOT_REQUIRED)
    private BigDecimal amount;

    @Schema(
            description = "Business reason justifying the adjustment",
            example = "Goodwill discount for delayed service",
            requiredMode = NOT_REQUIRED)
    private String reason;

    @Schema(
            description = "Identifier of the actor who authorized the adjustment",
            example = "jdoe",
            requiredMode = NOT_REQUIRED)
    private String authorizedBy;

    @Schema(
            description = "Optional correlation id to an external record (e.g. a warranty claim code)",
            example = "WC-2026-000042",
            requiredMode = NOT_REQUIRED)
    private String externalReference;

    @Schema(
            description = "Timestamp when the adjustment was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Nullable
    public UUID getId() {
        return id;
    }

    public void setId(@Nullable UUID id) {
        this.id = id;
    }

    @Nullable
    public InvoiceAdjustmentType getType() {
        return type;
    }

    public void setType(@Nullable InvoiceAdjustmentType type) {
        this.type = type;
    }

    @Nullable
    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(@Nullable BigDecimal amount) {
        this.amount = amount;
    }

    @Nullable
    public String getReason() {
        return reason;
    }

    public void setReason(@Nullable String reason) {
        this.reason = reason;
    }

    @Nullable
    public String getAuthorizedBy() {
        return authorizedBy;
    }

    public void setAuthorizedBy(@Nullable String authorizedBy) {
        this.authorizedBy = authorizedBy;
    }

    @Nullable
    public String getExternalReference() {
        return externalReference;
    }

    public void setExternalReference(@Nullable String externalReference) {
        this.externalReference = externalReference;
    }

    @Nullable
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(@Nullable Instant createdAt) {
        this.createdAt = createdAt;
    }
}
