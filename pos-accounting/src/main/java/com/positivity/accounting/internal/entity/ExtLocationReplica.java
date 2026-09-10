package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_location")
public class ExtLocationReplica extends TenantScopedEntity {

    @Id
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "name")
    private String name;

    /**
     * The owner's unique short code for the location (e.g. {@code LOC-107}). Replicated because
     * this module's GL dimension names a location by code, not by id, so a scope check has to map
     * the code back to the id the ancestor sets are keyed on (ADR-0061 §2, #1885).
     */
    @Column(name = "code", length = 100)
    private String code;

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

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by the owning module's envelope factory; this replica stores it verbatim and
     * generates no identifier of its own. Names {@link UUIDv7Generator} because that is the
     * dependency the cross-module {@code EntityStandardsArchitectureTest} recognises, matching
     * this module's other replicas (e.g. {@code ExtCustomerParty}).
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
