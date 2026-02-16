package com.positivity.invoice.internal.client;

import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;

public class TaxCalculationRequest {

    private BigDecimal subtotal;

    @NonNull
    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(@NonNull BigDecimal subtotal) {
        this.subtotal = subtotal;
    }
}
