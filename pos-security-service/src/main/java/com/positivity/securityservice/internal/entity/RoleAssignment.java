package com.positivity.securityservice.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import com.positivity.time.TimeSource;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Represents a user's effective-dated assignment to a role.
 *
 * <p>Carries no location scope. Location reach is a property of the role
 * ({@link Role#getLocationScope()} / {@link Role#getLocationHierarchy()}) combined with
 * pos-people's staffing assignment, per ADR-0061 §1; the former
 * {@code scope_type} column and {@code role_assignment_scope_locations} table were dropped by
 * {@code V38__drop_role_assignment_scope.sql} (#1875).
 */
@Data
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "role_assignments")
public class RoleAssignment extends TenantScopedEntity {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    /**
     * Start date when this assignment becomes effective
     */
    @Column(nullable = false)
    private LocalDateTime effectiveStartDate;

    /**
     * End date when this assignment expires (null = no expiration)
     */
    private LocalDateTime effectiveEndDate;

    /**
     * Timestamp when this assignment's revocation was entered, or null while it stands.
     *
     * <p>This is when someone <em>asked</em> for the revocation, which is deliberately independent
     * of {@link #effectiveEndDate} — the moment the revocation takes effect, which may be
     * backdated or scheduled for the future. Only {@link #revoke} sets it.
     *
     * <p>It used to be stamped by {@code setEffectiveEndDate}, which meant creating an ordinary
     * bounded assignment marked it revoked at birth: {@code createRoleAssignment} sets an end date
     * through that same setter. The field then read as "has an end date" rather than "was
     * revoked", so it could not answer the one question it exists for, and every bounded
     * assignment published a revocation that never happened (#1910).
     */
    @Setter(AccessLevel.PRIVATE)
    private Instant revokedAt;

    /**
     * When this assignment was created
     */
    @CreatedDate
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * User who created this assignment
     */
    @Column(nullable = false, length = 255)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Audit: When this assignment was last modified
     */
    private Instant lastModifiedAt;

    /**
     * Audit: User who last modified this assignment
     */
    @Column(length = 255)
    private String lastModifiedBy;

    @PrePersist
    protected void onCreate() {
        if (effectiveStartDate == null) {
            effectiveStartDate = TimeSource.localDateTime();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        lastModifiedAt = TimeSource.instant();
    }

    /**
     * Whether this assignment is effective now.
     *
     * <p>Prefer {@link #isEffectiveAt} wherever a caller holds a clock: this reads the ambient
     * {@link TimeSource}, which a test with a fixed clock cannot steer.
     */
    public boolean isEffective() {
        return isEffectiveAt(TimeSource.localDateTime());
    }

    /**
     * Whether this assignment is effective at {@code asOf}.
     *
     * <p>The window is half-open — start-inclusive, end-exclusive — which is what
     * {@code RoleAssignmentDto} has always published ("Exclusive end of the effective window")
     * and what the pos-people-contact edge already applied. This method and
     * {@code RoleAssignmentRepository.findEffectiveAssignmentsByUser} are the only places that
     * define it, and they must agree.
     *
     * <p>End-exclusive is what revocation needs: revoking sets {@code effectiveEndDate} to the
     * revocation instant, and the assignment has to stop being effective at that instant rather
     * than surviving it. It also lets a handover abut cleanly — one assignment ending at T and
     * the next starting at T do not overlap.
     */
    public boolean isEffectiveAt(LocalDateTime asOf) {
        boolean hasStarted = !asOf.isBefore(effectiveStartDate);
        boolean hasNotEnded = effectiveEndDate == null || asOf.isBefore(effectiveEndDate);
        return hasStarted && hasNotEnded;
    }

    /**
     * Revokes this assignment: the window ends at {@code endDate} and the revocation is recorded
     * as entered at {@code revokedAt}.
     *
     * <p>The two are separate on purpose. {@code endDate} may be backdated or scheduled ahead, so
     * a scheduled revocation stays effective until it arrives; {@code revokedAt} is always the
     * moment the instruction was given. Setting {@code effectiveEndDate} on its own — bounding a
     * new assignment, say — is not a revocation and must not stamp {@code revokedAt}.
     */
    public void revoke(LocalDateTime endDate, Instant revokedAt) {
        this.effectiveEndDate = endDate;
        this.revokedAt = revokedAt;
    }
}
