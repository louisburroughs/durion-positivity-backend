package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "A single line item on an invoice")
public class InvoiceItemResponse {

    @Schema(
            description = "Unique identifier of the invoice line item",
            example = "01960003-0000-7000-8000-000000000050",
            requiredMode = NOT_REQUIRED)
    private UUID id;

    @Schema(description = "Description of the line item", example = "Synthetic oil change", requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(description = "Quantity billed", example = "1", requiredMode = NOT_REQUIRED)
    private BigDecimal quantity;

    @Schema(description = "Price per unit", example = "49.99", requiredMode = NOT_REQUIRED)
    private BigDecimal unitPrice;

    @Schema(description = "Extended amount (quantity x unit price)", example = "49.99", requiredMode = NOT_REQUIRED)
    private BigDecimal amount;

    @Schema(
            description = "Identifier of the source workorder item",
            example = "01960003-0000-7000-8000-000000000051",
            requiredMode = NOT_REQUIRED)
    private UUID workorderItemId;

    @Schema(description = "Line item type (e.g. PART, LABOR, FEE, TAX)", example = "LABOR", requiredMode = NOT_REQUIRED)
    private String type;

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

    @Nullable
    public String getType() {
        return type;
    }

    public void setType(@Nullable String type) {
        this.type = type;
    }
}
