package com.positivity.accounting.internal.config;

import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where the tenant's purchases are taxed for self-assessed (use) tax (CAP:550 S43, AW44; ruling 5): the one place
 * pos-accounting reads {@code accounting.tax.purchase-place}. The country is {@link TaxCountry}'s; this adds the
 * region and postal code pos-tax needs to price {@code USE}. It follows {@link LedgerCurrency} and {@link TaxCountry}:
 * a Stage A, deployment-wide placeholder until bills carry a location (ADR-0044 R1 forbids reading pos-location's
 * address synchronously). Which jurisdiction's tax applies is held for expert advice (OI-4). No code branches on it.
 *
 * <p>The startup check: {@code postal-code} is required, at most 20 characters; {@code region-code} is optional, 1-3
 * letters or digits. Otherwise startup fails naming the property.
 */
@Component
public class PurchasePlace {

    static final String PROPERTY = "accounting.tax.purchase-place";

    private static final Pattern REGION = Pattern.compile("^[A-Za-z0-9]{1,3}$");
    private static final int MAX_POSTAL_CODE = 20;

    private final @Nullable String regionCode;
    private final String postalCode;

    public PurchasePlace(
            @Value("${" + PROPERTY + ".region-code:}") @Nullable String regionCode,
            @Value("${" + PROPERTY + ".postal-code:}") @Nullable String postalCode) {
        String region = regionCode == null || regionCode.isBlank() ? null : regionCode.trim();
        if (region != null && !REGION.matcher(region).matches()) {
            throw new IllegalStateException(
                    PROPERTY + ".region-code must be 1-3 letters or digits, or left empty, was '" + regionCode + "'");
        }
        String postal = postalCode == null ? "" : postalCode.trim();
        if (postal.isEmpty() || postal.length() > MAX_POSTAL_CODE) {
            throw new IllegalStateException(
                    PROPERTY + ".postal-code is required, at most " + MAX_POSTAL_CODE + " characters");
        }
        this.regionCode = region;
        this.postalCode = postal;
    }

    /** The region (state, province) code, or null when none is configured. */
    public @Nullable String regionCode() {
        return regionCode;
    }

    /** The postal code. */
    public @NonNull String postalCode() {
        return postalCode;
    }
}
