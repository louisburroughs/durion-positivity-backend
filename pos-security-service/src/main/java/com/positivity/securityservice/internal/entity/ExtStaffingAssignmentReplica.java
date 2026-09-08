package com.positivity.securityservice.internal.entity;

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
 * ({@code people.staffing-assignment.updated}; ADR-0061 §1, #1867).
 *
 * <p>pos-people owns which location node(s) an employee is assigned to; this module keeps a local
 * projection so access tokens can be issued from it (#1868). Nothing in this module may write the
 * table except the event consumer.
 *
 * <p>{@code locationId} is stored <em>as assigned</em>: it may be a shop or a District / Region /
 * HQ node. It is never expanded into descendants here — ADR-0061 §2 evaluates the hierarchy at
 * check time in the owning service.
 *
 * <p>{@code primary} is preserved verbatim for future use (#1876) and is NOT used to narrow the
 * assigned-node set: per the ADR-0061 amendment a person's set is every active assignment, so the
 * "several active assignments, no primary" population that pos-people's
 * {@code V3__backfill_primary_location_assignments.sql} calls genuinely ambiguous resolves to
 * their active nodes, not to nothing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_people_staffing_assignment")
public class ExtStaffingAssignmentReplica {

    /** Status value of an assignment that currently contributes to the person's node set. */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** Status value of an ended assignment; the row is retained for effective dating. */
    public static final String STATUS_ENDED = "ENDED";

    @Id
    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

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
