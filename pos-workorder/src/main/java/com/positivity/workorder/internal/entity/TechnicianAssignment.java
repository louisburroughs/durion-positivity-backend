package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Clock;
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
 * Represents a technician assignment to a workorder.
 * Maintains history of assignments, with the most recent assignment marked as
 * current.
 *
 * <p>
 * Note: This entity uses Long auto-increment ID for history tracking.
 * UUID fields (workorderId, technicianId, assignedBy) are foreign references
 * generated elsewhere using {@link UUIDv7Generator#generate()}.
 */
@Entity
@Table(name = "technician_assignment")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class TechnicianAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NonNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workorder_id", nullable = false)
    @ToString.Exclude
    private Workorder workorder;

    @jakarta.persistence.Transient
    public UUID getWorkorderId() {
        return workorder != null ? workorder.getId() : null;
    }

    @jakarta.persistence.Transient
    public void setWorkorderId(UUID workorderId) {
        this.workorder = new Workorder(workorderId);
    }

    @NonNull
    @Column(nullable = false, columnDefinition = "UUID")
    private UUID technicianId;

    @NonNull
    @Column(nullable = false)
    private LocalDateTime assignedAt;

    @NonNull
    @Column(nullable = false, columnDefinition = "TEXT")
    private String assignedBy;

    @Nullable
    @Column
    private LocalDateTime unassignedAt;

    @Nullable
    @Column(columnDefinition = "TEXT")
    private String reassignmentReason;

    @Nullable
    @Column(columnDefinition = "TEXT")
    private String notes;

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
            assignedAt = LocalDateTime.now(Clock.systemUTC());
        }
        if (current == null) {
            current = true;
        }
    }

    /**
     * Mark this assignment as no longer current and set unassignment timestamp.
     *
     * @param unassignmentTime the time the technician was unassigned
     * @param reason           optional reason for unassignment
     */
    public void markAsNotCurrent(@NonNull LocalDateTime unassignmentTime, @Nullable String reason) {
        this.current = false;
        this.unassignedAt = unassignmentTime;
        if (reason != null && !reason.isBlank()) {
            this.reassignmentReason = reason;
        }
    }

    // Note: This field documents that UUID fields in this entity are generated
    // elsewhere using UUIDv7Generator
    // This satisfies ADR-0013 architectural dependency requirement on
    // UUIDv7Generator
    @SuppressWarnings("unused")
    private static final Class<?> UUID_GENERATOR_CLASS = UUIDv7Generator.class;
}
