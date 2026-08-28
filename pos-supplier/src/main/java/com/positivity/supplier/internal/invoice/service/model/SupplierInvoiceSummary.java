package com.positivity.supplier.internal.invoice.service.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Summary of one vendor invoice fetched over the supplier invoice capability. Amounts are
 * verbatim from the vendor document — wrapper types with null meaning "not stated", never
 * zero. Placeholder-level for the CAP-317 foundation slice; grows with CAP-321.
 *
 * @param vendorProfileId vendor profile the invoice was fetched for
 * @param vendorInvoiceNumber vendor-native invoice identity, carried as an attribute
 *     (ADR-0013/0027); never blank
 * @param invoiceDate vendor document date
 * @param currency ISO 4217 currency of the amount fields; never blank
 * @param totalGrossAmount total gross amount, verbatim
 */
public record SupplierInvoiceSummary(
        @NonNull UUID vendorProfileId,
        @NonNull String vendorInvoiceNumber,
        @NonNull LocalDate invoiceDate,
        @NonNull String currency,
        @Nullable BigDecimal totalGrossAmount) {

    public SupplierInvoiceSummary {
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(vendorInvoiceNumber, "vendorInvoiceNumber must not be null");
        Objects.requireNonNull(invoiceDate, "invoiceDate must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (vendorInvoiceNumber.isBlank()) {
            throw new IllegalArgumentException("vendorInvoiceNumber must not be blank");
        }
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
    }
}
