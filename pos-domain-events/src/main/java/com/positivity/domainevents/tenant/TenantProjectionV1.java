package com.positivity.domainevents.tenant;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Public projection of a tenant (ADR-0062 §7): the payload of {@code tenant.updated},
 * {@code tenant.suspended}, {@code tenant.reactivated} and {@code tenant.decommissioned} on
 * {@code tenant.events.v1}.
 *
 * <p>Every module keeps this projection in a global {@code ext_tenant} replica keyed by {@code
 * tenantId}; {@code pos-security-service} resolves login slugs and serves {@code /v1/tenants/me}
 * from it. Consumers rebuild the whole replica row from each payload, so every field is carried on
 * every emission. New fields may be added additively within schema version 1.
 *
 * @param tenantId tenant identifier (also the envelope aggregateId and the Kafka record key)
 * @param slug URL-safe unique name the login route resolves from the {@code Host} header
 * @param displayName human-readable name
 * @param status raw lifecycle status: {@code PENDING}, {@code ACTIVE}, {@code SUSPENDED} or
 *     {@code DECOMMISSIONED}
 */
public record TenantProjectionV1(
        @NonNull UUID tenantId,
        @NonNull String slug,
        @Nullable String displayName,
        @NonNull String status) {

    public static final int SCHEMA_VERSION = 1;

    public TenantProjectionV1 {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
    }
}
