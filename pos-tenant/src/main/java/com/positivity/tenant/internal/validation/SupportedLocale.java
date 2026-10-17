package com.positivity.tenant.internal.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A BCP 47 language tag with a region, listed in {@code pos.tenant.supported-locales} (CAP:550 S44, #2618). Null
 * passes (an optional update field); the value is compared in canonical case, so {@code fr-ca} is {@code fr-CA}.
 */
@Documented
@Constraint(validatedBy = SupportedLocaleValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SupportedLocale {
    String message() default "must be a supported locale with a region (for example en-US)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
