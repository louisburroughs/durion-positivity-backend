package com.positivity.order.internal.exception;

import com.positivity.tax.common.error.TaxRefusals;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * pos-tax refused the order's calculation for a reason the caller must answer deterministically (#2644, ADR-0017):
 * 422 {@code ORDER_TAX_CURRENCY_NOT_SUPPORTED} (the functional currency is not the country profile's, ADR-0067 PC-9)
 * or 422 {@code ORDER_TAX_JURISDICTION_NOT_CONFIGURED} (no rates for the location). Unlike {@link
 * TaxUnavailableException}, a retry does not help: configuration does.
 */
public class OrderTaxRefusedException extends RuntimeException {

    public static final String CURRENCY_NOT_SUPPORTED = "ORDER_TAX_CURRENCY_NOT_SUPPORTED";
    public static final String JURISDICTION_NOT_CONFIGURED = "ORDER_TAX_JURISDICTION_NOT_CONFIGURED";

    private final String code;

    public OrderTaxRefusedException(@NonNull String code, @NonNull String message) {
        super(message);
        this.code = code;
    }

    public @NonNull String getCode() {
        return code;
    }

    /** This module's code for one of pos-tax's 422 codes, or null when pos-tax's code is not one of them. */
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
