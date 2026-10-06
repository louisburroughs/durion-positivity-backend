package com.positivity.domainevents.supplier;

import java.math.BigDecimal;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * One tax amount of a vendor document, by tax type, as the document states it (#2516).
 *
 * @param taxType the tax type as the document names it, e.g. {@code GST}, {@code PST}, {@code VAT}
 * @param amount  the amount of that tax, verbatim
 */
public record SupplierInvoiceTax(
        @NonNull String taxType, @NonNull BigDecimal amount) {

    public SupplierInvoiceTax {
        Objects.requireNonNull(taxType, "taxType must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }
}
