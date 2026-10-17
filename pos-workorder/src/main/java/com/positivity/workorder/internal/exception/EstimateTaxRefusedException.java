package com.positivity.workorder.internal.exception;

import org.jspecify.annotations.NonNull;

/**
 * pos-tax refused the estimate's calculation for a reason the caller must answer deterministically (#2644,
 * ADR-0017): 422 {@code ESTIMATE_TAX_CURRENCY_NOT_SUPPORTED} (the functional currency is not the country profile's,
 * ADR-0067 PC-9) or 422 {@code ESTIMATE_TAX_JURISDICTION_NOT_CONFIGURED} (no rates for the shop's location).
 */
public class EstimateTaxRefusedException extends RuntimeException {

    public static final String CURRENCY_NOT_SUPPORTED = "ESTIMATE_TAX_CURRENCY_NOT_SUPPORTED";
    public static final String JURISDICTION_NOT_CONFIGURED = "ESTIMATE_TAX_JURISDICTION_NOT_CONFIGURED";

    private final String code;

    public EstimateTaxRefusedException(@NonNull String code, @NonNull String message) {
        super(message);
        this.code = code;
    }

    public @NonNull String getCode() {
        return code;
    }

    /** This module's code for one of pos-tax's 422 codes, or null when pos-tax's code is not one of them. */
    public static String translate(String taxCode) {
        if ("CURRENCY_NOT_SUPPORTED".equals(taxCode)) {
            return CURRENCY_NOT_SUPPORTED;
        }
        if ("TAX_JURISDICTION_NOT_CONFIGURED".equals(taxCode)) {
            return JURISDICTION_NOT_CONFIGURED;
        }
        return null;
    }
}
