package com.positivity.domainevents.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code catalog.service.updated} on {@code catalog.events.v1} (ADR-0044 §6, #1306).
 *
 * <p>Published by pos-catalog after every {@code ServiceEntity} mutation. Services were the one
 * catalog item with no fact of their own, which left every consumer of {@code catalog.events.v1}
 * able to resolve a product reference and unable to resolve a service reference — pos-marketing
 * replicates this into its {@code ext_catalog} table so a campaign's {@code catalogFocusRef} can
 * name {@code service:alignment} and be checked before the campaign goes out.
 *
 * <p>The fact carries both the id and the name because a reference is written by hand as either
 * one; a consumer that received only the id could not follow {@code service:alignment} at all.
 *
 * <p>{@code active} exists because a consumer has to be able to refuse a retired service, and
 * pos-catalog's {@code ServiceEntity} carries no lifecycle column to read one from (unlike
 * {@code ProductEntity.status}). Deletion is the only retirement a service currently has, so an
 * upsert publishes {@code active = true} and a delete publishes a final fact with
 * {@code active = false} — a tombstone that leaves replicas holding the name that was removed
 * rather than silently keeping it resolvable. Should the entity later gain a real lifecycle flag,
 * it maps onto this field without a schema change.
 *
 * <p>pos-catalog's {@code ServiceEntity} has no JPA optimistic-lock {@code @Version}; as with
 * {@link ProductUpdatedV1}, the envelope's {@code aggregateVersion} carries {@code updatedAt} as
 * epoch millis, which increases per mutation and gives consumers a monotonic stale-event guard.
 *
 * <p>Schema version 2 (#1569, ADR-0058 §5, additive per ADR-0044 §3): appends the operation
 * taxonomy — {@code operationCode}, {@code operationCategory}, {@code defaultLaborHours}.
 * Version-1 consumers are unaffected and version-2 consumers must treat the new fields as
 * absent on old events. {@code defaultLaborHours} is the vehicle-agnostic fallback ONLY: it is
 * what a consumer may prefill when the labor-time resolution edge is unreachable, never the
 * vehicle-correct answer, and vehicle-keyed times never ride this fact (volume + licensing,
 * ADR-0058 §4).
 *
 * @param serviceId service identifier (also the envelope aggregateId)
 * @param name service display name
 * @param shortDescription short description, as shown in a picker
 * @param longDescription full description
 * @param active whether the service still exists in the catalog; false on the delete tombstone
 * @param createdAt owner row creation timestamp
 * @param updatedAt owner row last-update timestamp; null on the delete tombstone, whose
 *     {@code aggregateVersion} is the delete time rather than a row timestamp
 * @param operationCode Durion operation identity, e.g. {@code BRAKE-PAD-FRONT}. Additive within
 *     schema v2 (ADR-0044 §3)
 * @param operationCategory {@code REPAIR | DIAGNOSTIC | MAINTENANCE | TIRE_SERVICE}. Additive
 *     within schema v2
 * @param defaultLaborHours vehicle-agnostic fallback hours in tenths; degraded-mode prefill
 *     only. Additive within schema v2
 */
public record CatalogServiceUpdatedV1(
        @NonNull UUID serviceId,
        @Nullable String name,
        @Nullable String shortDescription,
        @Nullable String longDescription,
        boolean active,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt,
        @Nullable String operationCode,
        @Nullable String operationCategory,
        @Nullable BigDecimal defaultLaborHours) {

    public static final String EVENT_TYPE = "catalog.service.updated";
    public static final int SCHEMA_VERSION = 2;

    public CatalogServiceUpdatedV1 {
        if (serviceId == null) {
            throw new IllegalArgumentException("serviceId must not be null");
        }
    }
}
