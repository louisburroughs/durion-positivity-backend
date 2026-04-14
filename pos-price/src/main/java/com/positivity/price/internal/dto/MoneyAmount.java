package com.positivity.price.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * Monetary value wrapper.
 *
 * Issue: #51
 */
@Schema(description = "Monetary value object with amount and ISO-4217 currency code")
public class MoneyAmount {

    @Schema(description = "Numeric monetary amount", example = "125.50")
    private BigDecimal amount;

    @Schema(description = "ISO-4217 currency code", example = "USD")
    private String currency;

    public MoneyAmount() {}

    public MoneyAmount(BigDecimal amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
