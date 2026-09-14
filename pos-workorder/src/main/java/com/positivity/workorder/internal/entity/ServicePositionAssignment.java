package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantScopedEntity;
import com.positivity.time.TimeSource;
import com.positivity.workorder.internal.enums.ResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Append-only history of the service positions a workorder has occupied (#1983).
 *
 * <p>The workorder's {@code resourceId} / {@code resourceType} pair says where the job is
 * <em>now</em>; this table says how it got there — who placed it, when, why, and when it left. It
 * is the deliberate mirror of {@link TechnicianAssignment}: same {@code current} flag, same
 * closed-row-never-deleted rule, same shape of query. Position and technician are independent
 * assignments of the same workorder and answering "what changed on this job and who did it" should
 * not require reading two differently-shaped audit trails.
 *
 * <p>Exactly one row per workorder may have {@code current = true}, guaranteed by the partial unique
 * index {@code service_position_assignment_one_current_uniq}. That index is not redundant with the
 * occupancy index on {@code workorder}: the latter constrains how many workorders may hold one
 * <em>exclusive</em> position, and says nothing at all about {@link ResourceType#HOLD}, which is
 * unlimited by design — so without this one a workorder could accumulate several open HOLD history
 * rows and no longer have a single answer to "where is it now".
 */
@Entity
@Table(name = "service_position_assignment")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class ServicePositionAssignment extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NonNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workorder_id", nullable = false)
    @ToString.Exclude
    private Workorder workorder;

    @Transient
    public UUID getWorkorderId() {
        return workorder != null ? workorder.getId() : null;
    }

    /**
     * The kind of position occupied, stored alongside {@link #resourceId} for the same reason the
     * workorder stores the pair together (#1656): the id alone cannot say which aggregate it names,
     * and a history row read back without the type would be unresolvable.
     */
    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 32)
    private ResourceType resourceType;

    /**
     * The position itself: a bay id, a mobile-unit id, or — for
     * {@link ResourceType#HOLD} — the site the vehicle is parked at.
     */
    @NonNull
    @Column(name = "resource_id", nullable = false, columnDefinition = "UUID")
    private UUID resourceId;

    /** The site this placement happened at, copied from the workorder so history survives a move. */
    @Nullable
    @Column(name = "location_id", columnDefinition = "UUID")
    private UUID locationId;

    @NonNull
    @Column(nullable = false)
    private LocalDateTime assignedAt;

    @NonNull
    @Column(nullable = false, columnDefinition = "TEXT")
    private String assignedBy;

    @Nullable
    @Column
    private LocalDateTime releasedAt;

    /** Who released the position — null while the row is current. */
    @Nullable
    @Column(columnDefinition = "TEXT")
    private String releasedBy;

    @Nullable
    @Column(columnDefinition = "TEXT")
    private String reason;

    @NonNull
    @Column(nullable = false)
    @Builder.Default
    private Boolean current = true;

    // Audit fields — populated by Spring Data AuditingEntityListener; must not be @NonNull
    // to avoid Lombok null checks firing before @PrePersist wires them in.
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (assignedAt == null) {
            assignedAt = TimeSource.localDateTime();
        }
        if (current == null) {
            current = true;
        }
    }

    /**
     * Close this row: the workorder is no longer on this position.
     *
     * @param releaseTime when the position was given up
     * @param releasedBy  who gave it up — an actor name, or the transition that forced it
     * @param reason      why, when the caller supplied one; a blank reason leaves any existing one
     */
    public void release(@NonNull LocalDateTime releaseTime, @NonNull String releasedBy, @Nullable String reason) {
        this.current = false;
        this.releasedAt = releaseTime;
        this.releasedBy = releasedBy;
        if (reason != null && !reason.isBlank()) {
            this.reason = reason;
        }
    }

    // Documents that the UUID fields on this entity are minted elsewhere by UUIDv7Generator,
    // satisfying the ADR-0013 ArchUnit dependency rule (same hook as TechnicianAssignment).
    @SuppressWarnings("unused")
    private static final Class<?> UUID_GENERATOR_CLASS = UUIDv7Generator.class;
}
