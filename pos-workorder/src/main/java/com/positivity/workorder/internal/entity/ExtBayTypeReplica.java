package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One {@code BayType}'s replicated specialty claim (DECISION-LOCATION-025, #2261): whether the type
 * takes general work by default, plus the {@code aggregateVersion} of the tenant's whole bay
 * specialty map this row was last applied from.
 *
 * <p>Fed by {@code location.bay-specialty-map.updated} (owner {@code location}), a full replace of
 * the tenant's whole map on every emission — never a delta. {@code LocationEventsListener} deletes
 * every row for the tenant and reinserts one per {@code BaySpecialtyMapUpdatedV1.Entry} alongside
 * {@link ExtBaySpecialtyMapReplica} in the same handler transaction, so the two tables are never
 * inconsistent with each other. {@code aggregateVersion} is stamped identically on every row from
 * one emission — there is no single row the fact could otherwise version itself from, since the
 * aggregate is the tenant's whole map, not any one bay type — and the stale guard reads whichever
 * row happens to come back first.
 *
 * <p>An absent row for a {@code bayType} means the map has not arrived yet for this tenant, not
 * that the type takes no general work: readers must not default a missing row to {@code false}
 * (today's behaviour, unchanged until the map arrives).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "ext_bay_type",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_ext_bay_type_tenant_bay_type",
                        columnNames = {"tenant_id", "bay_type"}))
public class ExtBayTypeReplica extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /** A {@code BayType} name, e.g. {@code ALIGNMENT}. */
    @Column(name = "bay_type", nullable = false, length = 50)
    private String bayType;

    /** Whether this bay type takes general work by default; {@code false} only for {@code WASH_DETAIL}. */
    @Column(name = "accepts_general_work", nullable = false)
    private boolean acceptsGeneralWork;

    /** The tenant's whole-map {@code aggregateVersion} this row was last applied from. */
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
