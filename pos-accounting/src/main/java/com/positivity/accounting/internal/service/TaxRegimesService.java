package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.TaxRegimesResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The tax regimes a country configures (CAP:550 #2659): pos-tax's regimes, with their regions and tax types, for a given
 * country or the tenant's tax country ({@code accounting.tax.country}), relayed through accounting's front door
 * (ADR-0071, AW59) for the tax-registration panel. Reads only; nothing is stored or cached.
 */
public interface TaxRegimesService {

    /**
     * The regimes pos-tax configures for {@code countryCode}, or for the tax country when it is null.
     *
     * @param countryCode an upper-case ISO 3166-1 alpha-2 code (validated by the caller), or null for the tax country
     * @return the regimes; empty when the country configures none
     * @throws com.positivity.accounting.internal.exception.TaxServiceUnavailableException 503 when pos-tax cannot answer
     */
    @NonNull
    TaxRegimesResponse regimes(@Nullable String countryCode);
}
