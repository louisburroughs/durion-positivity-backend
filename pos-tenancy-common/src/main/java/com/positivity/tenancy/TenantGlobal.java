package com.positivity.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an entity whose table is deliberately not tenant-scoped (ADR-0062 §5): no {@code tenant_id}
 * discriminator, no row-level-security policy. The table must also be listed in the module's {@code
 * db/tenancy-global-tables.txt}, which the schema-conformance IT reads. Every other entity extends
 * {@link TenantScopedEntity}; the ArchUnit rule fails on one that does neither.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TenantGlobal {

    /** Why the table is global: the same reason recorded in {@code tenancy-global-tables.txt}. */
    String reason();
}
