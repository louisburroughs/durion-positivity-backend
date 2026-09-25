package com.positivity.inventory.internal.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;

/** Validates {@link NonZero}: any value but zero, compared by value so {@code 0.00} is zero too. */
public class NonZeroValidator implements ConstraintValidator<NonZero, BigDecimal> {

    @Override
    public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
        return value == null || value.signum() != 0;
    }
}
