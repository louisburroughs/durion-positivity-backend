package com.positivity.tenancy;

/**
 * Thrown by {@link TenantContext#require()} when no tenant is bound to the current thread. The
 * gateway filter turns the absence into a 401 before any handler runs; seeing this exception means
 * a non-request path (a scheduler, a listener, an executor) ran without binding a tenant.
 */
public class TenantContextMissingException extends IllegalStateException {

    public TenantContextMissingException() {
        super("No tenant is bound to the current thread (ADR-0062): bind one at the edge before touching"
                + " tenant-scoped data");
    }
}
