package com.positivity.tax.common.validation;

import com.neovisionaries.i18n.CountryCode;
import com.positivity.tax.common.dto.TaxCalculationRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;
import org.meeuw.i18n.subdivision.SubdivisionFactory;

/**
 * Validates country/subdivision membership using an ISO 3166-2 backed dataset.
 */
public class SubdivisionForCountryValidator
        implements ConstraintValidator<ValidSubdivisionForCountry, TaxCalculationRequest.TaxAddress> {

    private static final Set<String> US_SUBDIVISION_CODES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA",
            "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK",
            "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "DC", "AS", "GU", "MP",
            "PR", "UM", "VI");

    @Override
    public boolean isValid(TaxCalculationRequest.TaxAddress value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        String countryCode = value.getCountryCode();
        String regionCode = value.getRegionCode();

        if (countryCode == null || countryCode.isBlank() || regionCode == null || regionCode.isBlank()) {
            return true;
        }

        CountryCode country = CountryCode.getByCode(countryCode);
        if (country == null) {
            return false;
        }

        String normalizedRegion = normalizeRegion(countryCode, regionCode);
        if (SubdivisionFactory.getSubdivision(country, normalizedRegion) != null) {
            return true;
        }

        if (CountryCode.US.equals(country)) {
            return US_SUBDIVISION_CODES.contains(normalizedRegion);
        }

        return false;
    }

    private String normalizeRegion(String countryCode, String regionCode) {
        String normalized = regionCode.trim().toUpperCase();
        String prefix = countryCode.trim().toUpperCase() + "-";

        if (normalized.startsWith(prefix)) {
            return normalized.substring(prefix.length());
        }

        return normalized;
    }
}
