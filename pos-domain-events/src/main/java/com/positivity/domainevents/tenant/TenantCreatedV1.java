package com.positivity.domainevents.tenant;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a tenant was registered in {@code pos-tenant} (ADR-0062 §7), published on
 * {@code tenant.events.v1} with {@code eventType = "tenant.created"} and status {@code PENDING}.
 *
 * <p>Besides the public projection it carries the provisioning input {@code pos-security-service}
 * needs: the initial administrator's email. That handler runs under
 * {@code TenantContext.runAs(tenantId)}, applies the role template, creates the administrator and
 * answers with {@code tenant.provisioned}. Other modules seed their own per-tenant defaults from
 * this event inside their own domain. Every handler must be idempotent on {@code tenantId}.
 *
 * @param tenantId tenant identifier (also the envelope aggregateId)
 * @param slug URL-safe unique name
 * @param displayName human-readable name
 * @param status always {@code PENDING} on this event
 * @param initialAdminEmail email of the first administrator to create; provisioning input, not
 *     part of the {@code ext_tenant} projection
 */
public record TenantCreatedV1(
        @NonNull UUID tenantId,
        @NonNull String slug,
        @Nullable String displayName,
        @NonNull String status,
        @NonNull String initialAdminEmail) {

    public static final String EVENT_TYPE = TenantEventTypes.CREATED;
    public static final int SCHEMA_VERSION = 1;

    public TenantCreatedV1 {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        if (initialAdminEmail == null || initialAdminEmail.isBlank()) {
            throw new IllegalArgumentException("initialAdminEmail must not be blank");
        }
    }

    /** The public projection consumers keep in {@code ext_tenant}. */
    public @NonNull TenantProjectionV1 projection() {
        return new TenantProjectionV1(tenantId, slug, displayName, status);
    }
}
