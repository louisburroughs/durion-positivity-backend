package com.positivity.securityservice.internal.exception;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A platform-only operation was called under a tenant binding other than the platform tenant
 * (ADR-0062 §7). Mapped to 403 {@code PLATFORM_TENANT_REQUIRED} by {@code GlobalExceptionHandler},
 * the same answer {@code pos-tenant}'s {@code PlatformTenantGuard} gives at its edge.
 */
public class PlatformTenantRequiredException extends RuntimeException {

    public PlatformTenantRequiredException(@Nullable UUID boundTenantId) {
        super(
                boundTenantId == null
                        ? "This operation requires the platform tenant binding; none is bound"
                        : "This operation requires the platform tenant binding; bound to " + boundTenantId);
    }
}
