package com.positivity.tenancy.replica;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One {@code tenant.events.v1} fact as an {@code ext_tenant} replica consumer needs it (ADR-0062
 * §7): the envelope identity plus the public projection. Every module keeps such a replica; this
 * is the shared parse so each listener is the upsert and nothing else.
 *
 * <p>{@code tenant.created}, {@code tenant.updated}, {@code tenant.suspended}, {@code
 * tenant.reactivated} and {@code tenant.decommissioned} all carry the projection and are applied
 * the same way, guarded by {@link #aggregateVersion()} (an older fact never overwrites a newer row).
 * {@code tenant.provisioned} carries only the tenant id and is not a projection: {@link #parse}
 * returns empty for it, as for anything malformed, so the consumer records and skips it.
 *
 * @param eventId envelope id, the idempotency key
 * @param eventType dotted event type
 * @param aggregateVersion owner's per-tenant sequence
 * @param tenantId the tenant the projection describes
 * @param slug URL-safe unique name
 * @param displayName human-readable name, may be absent
 * @param status raw lifecycle status
 */
public record TenantProjectionEvent(
        @NonNull String eventId,
        @NonNull String eventType,
        long aggregateVersion,
        @NonNull UUID tenantId,
        @NonNull String slug,
        @Nullable String displayName,
        @NonNull String status) {

    /** Event types whose payload is the public projection. */
    public static final Set<String> PROJECTION_EVENT_TYPES = Set.of(
            "tenant.created", "tenant.updated", "tenant.suspended", "tenant.reactivated", "tenant.decommissioned");

    /** The status a tenant must hold for logins and per-tenant jobs. */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /**
     * Parse a serialized {@code DomainEventEnvelope} from {@code tenant.events.v1}; empty when the
     * type is not a projection fact or any required field is missing or malformed.
     */
    public static Optional<TenantProjectionEvent> parse(@NonNull JsonNode root) {
        String eventType = root.path("eventType").stringValue(null);
        String eventId = root.path("eventId").stringValue(null);
        if (eventType == null || eventId == null || !PROJECTION_EVENT_TYPES.contains(eventType)) {
            return Optional.empty();
        }
        JsonNode payload = root.path("payload");
        String rawTenantId = payload.path("tenantId").stringValue(null);
        String slug = payload.path("slug").stringValue(null);
        String status = payload.path("status").stringValue(null);
        if (rawTenantId == null || slug == null || slug.isBlank() || status == null || status.isBlank()) {
            return Optional.empty();
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(rawTenantId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        JsonNode version = root.path("aggregateVersion");
        if (!version.isIntegralNumber() || version.longValue() < 0) {
            // The version guard is what keeps a replica monotonic; a fact without one cannot be ordered.
            return Optional.empty();
        }
        return Optional.of(new TenantProjectionEvent(
                eventId,
                eventType,
                version.longValue(),
                tenantId,
                slug,
                payload.path("displayName").stringValue(null),
                status));
    }

    /** True when the projection says the tenant may log in and be iterated. */
    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
