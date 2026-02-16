package com.positivity.invoice.internal.dto;

import com.positivity.invoice.internal.enums.InvoiceAdjustmentType;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class InvoiceAdjustmentResponse {

    private UUID id;
    private InvoiceAdjustmentType type;
    private BigDecimal amount;
    private String reason;
    private String authorizedBy;
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
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(@Nullable Instant createdAt) {
        this.createdAt = createdAt;
    }
}
