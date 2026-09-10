package com.positivity.domainevents.tenant;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Fact: {@code pos-security-service} finished provisioning a tenant (ADR-0062 §7): role template
 * applied, initial administrator created, first role assignment written. Published on
 * {@code tenant.events.v1} with {@code eventType = "tenant.provisioned"}; the one event on that
 * topic whose producer is not {@code pos-tenant}.
 *
 * <p>{@code pos-tenant} handles it by moving the tenant from {@code PENDING} to {@code ACTIVE} and
 * publishing {@code tenant.updated} with the new status, so replicas learn the status from the
 * registry owner. Idempotent on {@code tenantId}: a tenant that is already {@code ACTIVE} (or
 * beyond) is left alone.
 *
 * @param tenantId the provisioned tenant (also the envelope aggregateId)
 */
public record TenantProvisionedV1(@NonNull UUID tenantId) {

    public static final String EVENT_TYPE = TenantEventTypes.PROVISIONED;
    public static final int SCHEMA_VERSION = 1;

    public TenantProvisionedV1 {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
    }
}
