package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only staffing-assignment replica fed by {@code people.events.v1}
 * ({@code people.staffing-assignment.updated}, ADR-0044 §6, #877). Drives local availability
 * views and primary-location resolution; pos-people owns the facts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_people_staffing_assignment")
public class ExtStaffingAssignmentReplica {

    @Id
    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "role", length = 100)
    private String role;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
