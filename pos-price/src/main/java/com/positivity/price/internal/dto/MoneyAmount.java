package com.positivity.price.internal.dto;

import java.math.BigDecimal;

/**
 * Monetary value wrapper.
 *
 * Issue: #51
 */
public class MoneyAmount {

    private BigDecimal amount;
    private String currency;

    public MoneyAmount() {
    }

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
