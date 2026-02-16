package com.positivity.invoice.internal.dto;

import com.positivity.invoice.internal.enums.InvoiceAdjustmentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

public class AdjustmentRequest {

    @NotNull
    private InvoiceAdjustmentType type;

    @NotNull
    @DecimalMin(value = "0.0001")
    private BigDecimal amount;

    @NotBlank
    private String reason;

    @NotBlank
    private String authorizedBy;

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
}
