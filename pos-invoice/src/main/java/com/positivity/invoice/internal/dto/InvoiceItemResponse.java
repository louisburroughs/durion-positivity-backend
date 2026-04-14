package com.positivity.invoice.internal.dto;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class InvoiceItemResponse {

    private UUID id;
    private String description;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private UUID workorderItemId;

    @Nullable
    public UUID getId() {
        return id;
    }

    public void setId(@Nullable UUID id) {
        this.id = id;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    public void setDescription(@Nullable String description) {
        this.description = description;
    }

    @Nullable
    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(@Nullable BigDecimal quantity) {
        this.quantity = quantity;
    }

    @Nullable
    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(@Nullable BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    @Nullable
    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(@Nullable BigDecimal amount) {
        this.amount = amount;
    }

    @Nullable
    public UUID getWorkorderItemId() {
        return workorderItemId;
    }

    public void setWorkorderItemId(@Nullable UUID workorderItemId) {
        this.workorderItemId = workorderItemId;
    }
}
