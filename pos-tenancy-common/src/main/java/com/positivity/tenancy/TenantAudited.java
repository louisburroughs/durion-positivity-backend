package com.positivity.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Review marker for native SQL and {@code JdbcTemplate} access to tenant-scoped data (ADR-0062 §5).
 * Hibernate's tenant filter does not apply to native queries; row-level security still does. The
 * annotation records that a reviewer confirmed the statement reads scoped tables only through the
 * bound connection and adds no tenant of its own. The ArchUnit rule fails on an unannotated native
 * query in a tenant-scoped entity's repository.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface TenantAudited {

    /** What the reviewer established. */
    String reason();
}
