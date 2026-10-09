package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * pos-tax's purchase-tax rules of a country on a date ({@code GET /v1/tax/purchase-rules}, CAP:550 S43, AW44), as
 * pos-accounting reads them through {@code TaxReferenceClient}: never served by an accounting endpoint. Every value is
 * pos-tax configuration held for expert advice ({@code source = STUB}).
 *
 * @param countryCode               the country, echoed
 * @param asOf                      the date, echoed
 * @param source                    always {@code STUB}
 * @param configured                whether the country configures purchase-tax rules
 * @param taxOnResaleGoods          {@code HOLD} or {@code ALLOW}
 * @param selfAssessUntaxedExpenses whether an untaxed expense bill self-assesses (use) tax
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaxPurchaseRules(
        @Nullable String countryCode,
        @Nullable LocalDate asOf,
        @Nullable String source,
        boolean configured,
        @Nullable String taxOnResaleGoods,
        boolean selfAssessUntaxedExpenses) {

    /** The {@code taxOnResaleGoods} value that holds a bill charging tax on goods for resale. */
    public static final String HOLD = "HOLD";

    /** Whether a bill charging tax on goods for resale is held: configured, and {@code HOLD}. */
    public boolean holdsTaxOnResaleGoods() {
        return configured && HOLD.equals(taxOnResaleGoods);
    }

    /** Whether an untaxed expense bill self-assesses (use) tax: configured, and switched on. */
    public boolean selfAssessesUntaxedExpenses() {
        return configured && selfAssessUntaxedExpenses;
    }
}
