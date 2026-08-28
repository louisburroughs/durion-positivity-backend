package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxRateLookupResponse;
import java.time.LocalDate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Public API for the jurisdiction rate lookup (issue #1522).
 * <p>
 * Internal-only per ADR-0021/ADR-0014 — pos-tax has no gateway route, so this is reached only
 * by direct in-cluster calls, not through {@code pos-api-gateway}. In test mode this resolves
 * rates from the same address/effective-date rule logic {@link TaxCalculationService} uses for
 * {@code /calculate}. When the configured production provider does not support a rate-only
 * call (every provider today), lookup fails with a {@code TaxRateLookupUnsupportedException}
 * (surfaced as HTTP 501) rather than synthesizing an estimate.
 */
public interface TaxRateLookupService {

    /**
     * Resolve the per-jurisdiction tax rates applicable to a destination address.
     *
     * @param countryCode country code in ISO 3166-1 alpha-2 format
     * @param regionCode  optional region/subdivision code (e.g. a US state)
     * @param city        optional city/locality name
     * @param postalCode  postal/ZIP code
     * @param asOf        the date to resolve rates as of; {@code null} defaults to today
     * @return the resolved rate components and combined rate
     * @throws RuntimeException when the active provider does not support rate-only lookup
     *     (surfaced by the controller as HTTP 501)
     */
    @NonNull
    TaxRateLookupResponse lookupRates(
            @NonNull String countryCode,
            @Nullable String regionCode,
            @Nullable String city,
            @NonNull String postalCode,
            @Nullable LocalDate asOf);
}
