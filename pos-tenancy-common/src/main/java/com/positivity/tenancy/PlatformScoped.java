package com.positivity.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a scheduled method that runs with no tenant bound (ADR-0062 §3): it may touch only {@link
 * TenantGlobal} tables. Row-level security makes every scoped table read as empty and refuse
 * inserts for such a job, so a misclassified job is a no-op, not a leak. Every other {@code
 * @Scheduled} method iterates tenants through {@link TenantIterator}; the ArchUnit rule fails on
 * one that does neither. Platform-scoped jobs are listed in the module README.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PlatformScoped {

    /** Which global tables the job touches and why it needs no tenant. */
    String reason();
}
