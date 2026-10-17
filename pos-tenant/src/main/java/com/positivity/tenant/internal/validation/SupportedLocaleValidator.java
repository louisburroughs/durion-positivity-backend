package com.positivity.tenant.internal.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** Bean-validation side of {@link TenantLocales}: a Spring-managed validator, so the allow-list is configuration. */
@RequiredArgsConstructor
public class SupportedLocaleValidator implements ConstraintValidator<SupportedLocale, String> {

    private final TenantLocales locales;

    @Override
    public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return locales.canonicalOf(value).isPresent();
    }
}
