package com.positivity.tax.internal.validation;

import com.neovisionaries.i18n.CurrencyCode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator for ISO 4217 currency codes.
 */
public class IsoCurrencyCodeValidator implements ConstraintValidator<IsoCurrencyCode, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }

        return CurrencyCode.getByCode(value) != null;
    }
}
