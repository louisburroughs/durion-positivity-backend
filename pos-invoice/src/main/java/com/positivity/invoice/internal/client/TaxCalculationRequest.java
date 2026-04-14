package com.positivity.invoice.internal.client;

import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;

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
