package com.positivity.invoice.internal.client;

import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;

public class TaxCalculationResponse {

    private BigDecimal taxAmount;
    private BigDecimal rate;

    @NonNull
    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(@NonNull BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    @NonNull
    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(@NonNull BigDecimal rate) {
        this.rate = rate;
    }
}
