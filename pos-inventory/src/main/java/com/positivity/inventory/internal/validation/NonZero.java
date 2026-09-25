package com.positivity.inventory.internal.validation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * The annotated {@link java.math.BigDecimal} must not be zero (issue #2201). Negative and positive
 * values are valid; {@code null} is valid too, so pair it with {@code @NotNull} when the value is
 * required.
 */
@Documented
@Constraint(validatedBy = NonZeroValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface NonZero {

    String message() default "must not be zero";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
