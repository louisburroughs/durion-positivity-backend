package com.positivity.catalog.internal.entity;

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
 * Read-only location replica fed by {@code location.events.v1} (ADR-0044 §6, #892).
 *
 * <p>pos-location owns these facts; nothing in this module may write the table except the event
 * consumer. This module replicates locations for exactly one reason — the two materialised,
 * inclusive-of-self location-scope ancestor sets (ADR-0061 §2, #1885), recomputed by the consumer
 * from the {@link ExtLocationParentReplica} edges on every location fact. A scope check intersects
 * the caller's assigned nodes with the set for the role's hierarchy dimension; a location the
 * replica does not hold yields empty sets and therefore denies.
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

    @Column(name = "name")
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

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

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by the owning module's envelope factory; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
