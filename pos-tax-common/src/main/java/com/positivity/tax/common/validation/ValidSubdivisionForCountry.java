package com.positivity.tax.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a subdivision code belongs to the provided country code.
 */
@Documented
@Constraint(validatedBy = SubdivisionForCountryValidator.class)
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSubdivisionForCountry {

    String message() default "regionCode must be a valid subdivision for countryCode";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
