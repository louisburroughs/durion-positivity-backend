package com.positivity.securityservice.internal.exception;

import java.util.UUID;

/**
 * A platform operation named a tenant this module's {@code ext_tenant} replica does not hold
 * (ADR-0062 §7). Mapped to 404 {@code TENANT_NOT_FOUND} by {@code GlobalExceptionHandler}.
 */
public class TenantNotFoundException extends RuntimeException {

    public TenantNotFoundException(UUID tenantId) {
        super("Tenant not found: " + tenantId);
    }
}
