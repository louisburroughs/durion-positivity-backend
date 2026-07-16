package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.invoice.internal.enums.InvoiceAdjustmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "Request to apply a monetary adjustment to an invoice")
public class AdjustmentRequest {

    @NotNull
    @Schema(description = "Type of adjustment applied to the invoice", example = "DISCOUNT", requiredMode = REQUIRED)
    private InvoiceAdjustmentType type;

    @NotNull
    @DecimalMin(value = "0.0001")
    @Schema(description = "Adjustment amount (must be greater than zero)", example = "25.00", requiredMode = REQUIRED)
    private BigDecimal amount;

    @NotBlank
    @Schema(
            description = "Business reason justifying the adjustment",
            example = "Goodwill discount for delayed service",
            requiredMode = REQUIRED)
    private String reason;

    @NotBlank
    @Schema(
            description = "Identifier of the actor who authorized the adjustment",
            example = "jdoe",
            requiredMode = REQUIRED)
    private String authorizedBy;

    @Size(max = 64)
    @Schema(
            description = "Optional correlation id to an external record (e.g. a warranty claim code)",
            example = "WC-2026-000042",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String externalReference;

    @Nullable
    public InvoiceAdjustmentType getType() {
        return type;
    }

    public void setType(@NonNull InvoiceAdjustmentType type) {
        this.type = type;
    }

    @Nullable
    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(@NonNull BigDecimal amount) {
        this.amount = amount;
    }

    @Nullable
    public String getReason() {
        return reason;
    }

    public void setReason(@NonNull String reason) {
        this.reason = reason;
    }

    @Nullable
    public String getAuthorizedBy() {
        return authorizedBy;
    }

    public void setAuthorizedBy(@NonNull String authorizedBy) {
        this.authorizedBy = authorizedBy;
    }

    @Nullable
    public String getExternalReference() {
        return externalReference;
    }

    public void setExternalReference(@Nullable String externalReference) {
        this.externalReference = externalReference;
    }
}
