package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One interval a workorder held a service position for, mirrored from the owner's history
 * ({@code WorkorderUpdatedV1.positions}, #2530), replace-set per fact.
 *
 * <p>{@code ExtWorkorderReplica} says only where the job is <em>now</em>, and the owner clears that
 * when the job closes. This is the durable record of where the work was done, which is what the
 * capacity read charges a bay for on a date that has already happened.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_workorder_position")
public class ExtWorkorderPositionReplica extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workorder_id", nullable = false, columnDefinition = "UUID")
    private UUID workorderId;

    /** The owner's enum name: {@code BAY}, {@code MOBILE_UNIT} or {@code HOLD}. */
    @Column(name = "resource_type", nullable = false, length = 32)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, columnDefinition = "UUID")
    private UUID resourceId;

    @Nullable
    @Column(name = "location_id", columnDefinition = "UUID")
    private UUID locationId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    /** Null while the workorder still holds the position. */
    @Nullable
    @Column(name = "released_at")
    private Instant releasedAt;
}
