package com.positivity.shared.id;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.hibernate.annotations.IdGeneratorType;

/**
 * Marks an identifier field to be generated using {@link UUIDv7Generator}.
 */
@IdGeneratorType(UUIDv7HibernateGenerator.class)
@Retention(RUNTIME)
@Target({ FIELD, METHOD })
public @interface UUIDv7Id {
}
