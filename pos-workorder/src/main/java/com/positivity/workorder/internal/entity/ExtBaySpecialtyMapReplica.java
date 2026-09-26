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
 * One catalog operation code a {@code BayType} is the only type able to perform (CAP-325 D14,
 * DECISION-LOCATION-025) — replicated from pos-location's {@code bay_specialty_operation} via
 * {@code location.bay-specialty-map.updated}.
 *
 * <p>Full replace-set per tenant on every emission: the fact carries the whole map, never a delta,
 * so {@code LocationEventsListener} deletes every row for the tenant and reinserts one per claimed
 * operation code, alongside the {@link ExtBayTypeReplica} rows, in the same handler transaction.
 *
 * <p>An absent row for an operation code means it is general work — performable by any bay whose
 * type's {@link ExtBayTypeReplica#isAcceptsGeneralWork()} is true — or that the map has not arrived
 * yet for this tenant; the two read the same here, which is exactly today's pre-replica behaviour
 * (no operation is treated as specialty until the map lands).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "ext_bay_specialty_map",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_ext_bay_specialty_map_tenant_bay_op",
                        columnNames = {"tenant_id", "bay_type", "operation_code"}))
public class ExtBaySpecialtyMapReplica extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /** A {@code BayType} name, e.g. {@code ALIGNMENT}. */
    @Column(name = "bay_type", nullable = false, length = 50)
    private String bayType;

    /** A catalog {@code operationCode}, {@code UPPER-DASH} per ADR-0059 §3. */
    @Column(name = "operation_code", nullable = false, length = 64)
    private String operationCode;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
