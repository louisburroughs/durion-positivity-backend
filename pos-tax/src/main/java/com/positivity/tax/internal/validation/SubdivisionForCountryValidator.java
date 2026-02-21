package com.positivity.tax.internal.validation;

import com.neovisionaries.i18n.CountryCode;
import com.positivity.tax.internal.dto.TaxCalculationRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.meeuw.i18n.subdivision.SubdivisionFactory;

/**
 * Validates country/subdivision membership using an ISO 3166-2 backed dataset.
 */
public class SubdivisionForCountryValidator
        implements ConstraintValidator<ValidSubdivisionForCountry, TaxCalculationRequest.TaxAddress> {

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
        return SubdivisionFactory.getSubdivision(country, normalizedRegion) != null;
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
