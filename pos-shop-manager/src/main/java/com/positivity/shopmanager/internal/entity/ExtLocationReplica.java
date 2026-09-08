package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only location replica fed by {@code location.events.v1} (ADR-0044 §6, ADR-0061 §2, #1872).
 *
 * <p>pos-location owns the location aggregate; only {@link
 * com.positivity.shopmanager.internal.service.LocationEventsListener} writes this table (R3). It
 * exists for one reason: the in-process location-scope check. Every location-parameterised endpoint
 * in this module gates the caller's scope against the requested location, and the check needs the
 * location's ancestor sets without a per-request call to pos-location.
 *
 * <p>The two ancestor sets are materialised, inclusive of the location itself, and recomputed by
 * the consumer from the {@link ExtLocationParentReplica} edges on every location fact. A scope
 * check intersects the caller's assigned nodes with the set for the role's hierarchy dimension; a
 * location the replica does not hold yields empty sets and therefore denies (fail closed).
 *
 * <p>Not to be confused with this module's {@link Shop} entity: {@code shop.id} is the same
 * pos-location id by convention (every service resolves a request's {@code locationId} through
 * {@code ShopRepository}), but {@code shop} is this module's own scheduling configuration, not a
 * replica, and carries no hierarchy.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_location")
public class ExtLocationReplica {

    @Id
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    /** When this row was last written from a fact, on this module's clock. */
    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    /** Ancestors along the {@code FINANCIAL} parent chain, inclusive of this location (ADR-0061 §2). */
    @Builder.Default
    @Convert(converter = UuidSetConverter.class)
    @Column(name = "financial_ancestor_ids", nullable = false, columnDefinition = "text")
    private Set<UUID> financialAncestorIds = new LinkedHashSet<>();

    /**
     * Ancestors along the union of every non-financial parent type (HOME_OFFICE, HEADQUARTERS,
     * REGION, DISTRICT, PHYSICAL, ORGANIZATIONAL, SHIPPING), inclusive of this location (ADR-0061 §2).
     */
    @Builder.Default
    @Convert(converter = UuidSetConverter.class)
    @Column(name = "other_ancestor_ids", nullable = false, columnDefinition = "text")
    private Set<UUID> otherAncestorIds = new LinkedHashSet<>();

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
