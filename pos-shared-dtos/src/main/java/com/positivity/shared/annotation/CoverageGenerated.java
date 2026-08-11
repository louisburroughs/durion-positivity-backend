package com.positivity.shared.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks generated or mechanically emitted code that JaCoCo should exclude from
 * coverage analysis.
 *
 * <p>
 * Do not apply this annotation to handwritten business behavior.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR })
public @interface CoverageGenerated {
}
