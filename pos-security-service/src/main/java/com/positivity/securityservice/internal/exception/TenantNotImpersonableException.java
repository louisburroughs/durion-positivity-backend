package com.positivity.securityservice.internal.exception;

import java.util.UUID;

/**
 * The tenant exists but an impersonation token cannot be minted for it (ADR-0062 §7, WS2b-4):
 * it is not {@code ACTIVE} in the {@code ext_tenant} replica, or it has no {@code SUPPORT} role
 * yet (provisioned before the role joined the template and not reconciled). Mapped to 409
 * {@code TENANT_NOT_IMPERSONABLE} by {@code GlobalExceptionHandler}; the message says which.
 */
public class TenantNotImpersonableException extends RuntimeException {

    public TenantNotImpersonableException(UUID tenantId, String reason) {
        super("Tenant " + tenantId + " cannot be impersonated: " + reason);
    }
}
