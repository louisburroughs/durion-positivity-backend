package com.positivity.securityservice.internal.exception;

import java.util.UUID;

/**
 * The tenant named by a platform operation is not in the {@code ext_tenant} replica (ADR-0062 §7,
 * WS2b-4): either it does not exist or its {@code tenant.created} fact has not reached this
 * module yet. Mapped to 404 {@code TENANT_NOT_FOUND} by {@code GlobalExceptionHandler}.
 */
public class TenantNotFoundException extends RuntimeException {

    public TenantNotFoundException(UUID tenantId) {
        super("Tenant not found: " + tenantId);
    }
}
