package com.positivity.invoice.internal.exception;

import com.positivity.tax.common.error.TaxRefusals;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * pos-tax refused the calculation for a reason the caller must answer deterministically (#2644, ADR-0017):
 * 422 {@code INVOICE_TAX_CURRENCY_NOT_SUPPORTED} (the tenant's functional currency is not the country profile's,
 * ADR-0067 PC-9) or 422 {@code INVOICE_TAX_JURISDICTION_NOT_CONFIGURED} (no rates for the destination).
 */
public class InvoiceTaxRefusedException extends RuntimeException {

    public static final String CURRENCY_NOT_SUPPORTED = "INVOICE_TAX_CURRENCY_NOT_SUPPORTED";
    public static final String JURISDICTION_NOT_CONFIGURED = "INVOICE_TAX_JURISDICTION_NOT_CONFIGURED";

    private final String code;

    public InvoiceTaxRefusedException(@NonNull String code, @NonNull String message) {
        super(message);
        this.code = code;
    }

    public @NonNull String getCode() {
        return code;
    }

    /** The caller's code for one of pos-tax's 422 codes, or null when pos-tax's code is not one of them. */
    public static @Nullable String translate(@Nullable String taxCode) {
        if (TaxRefusals.CURRENCY_NOT_SUPPORTED.equals(taxCode)) {
            return CURRENCY_NOT_SUPPORTED;
        }
        if (TaxRefusals.TAX_JURISDICTION_NOT_CONFIGURED.equals(taxCode)) {
            return JURISDICTION_NOT_CONFIGURED;
        }
        return null;
    }
}
