package com.positivity.people.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Read-only role-assignment replica fed by {@code security.role-assignment.changed}
 * (RoleAssignmentChangedV1) on {@code security.events.v1} (ADR-0044 §6, durion#2155/#2160).
 *
 * <p>pos-security-service owns role assignments; nothing in this module may write this table
 * except {@link com.positivity.people.internal.service.SecurityEventsListener}. The payload is a
 * full snapshot covering both grant and revoke — there is no separate "removed" event type — so a
 * revoke is applied as an update ({@link #effectiveEndDate} / {@link #revokedAt} set), never a
 * delete; a row's lifetime spans the whole life of its assignment.
 *
 * <p>Keyed by {@code assignmentId} (the event's {@code aggregateId}), matching the producer's
 * identity. {@link #username} is denormalized here rather than resolved via a join at read time —
 * it is the join key back to an employee ({@code personId}), through {@link ExtUserLinkReplica},
 * because employees are keyed by {@code personId} while assignments are keyed by
 * {@code userId}/{@code username} and {@code ExtUserLinkReplica} has no {@code userId} column.
 *
 * <p>{@link #roleLocationScope} is {@code Role.locationScope} ({@code ALL} or {@code LOCATION}),
 * denormalized onto the event at publish time by pos-security-service — it is a property of the
 * role, not the assignment (ADR-0061 §1; {@code RoleAssignment} itself carries no scope). See
 * {@code RoleAssignmentChangedV1}'s class javadoc.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_role_assignment_replica")
public class ExtRoleAssignmentReplica extends TenantScopedEntity {

    @Id
    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "role_name", nullable = false)
    private String roleName;

    @Column(name = "role_location_scope", nullable = false, length = 50)
    private String roleLocationScope;

    @Column(name = "effective_start_date", nullable = false)
    private LocalDateTime effectiveStartDate;

    @Column(name = "effective_end_date")
    private LocalDateTime effectiveEndDate;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by pos-security-service; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
