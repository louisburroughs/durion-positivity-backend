package com.positivity.securityservice.internal.entity;

import com.positivity.securityservice.internal.enums.ScopeType;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Represents a user's assignment to a role with optional scope and effective
 * dating.
 * Supports scoped RBAC where roles can be limited to specific locations.
 */
@Data
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "role_assignments")
public class RoleAssignment {
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
     * Scope type for this role assignment
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScopeType scopeType = ScopeType.GLOBAL;

    /**
     * Location IDs this role assignment applies to (only used when scopeType is
     * LOCATION)
     */
    @ElementCollection
    @CollectionTable(name = "role_assignment_scope_locations", joinColumns = @JoinColumn(name = "role_assignment_id"))
    @Column(name = "location_id")
    private Set<String> scopeLocationIds = new HashSet<>();

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
     * Timestamp when this assignment's revocation was last set or updated.
     * Updated automatically whenever effectiveEndDate is set.
     * Always set to current timestamp (cannot be backdated or future-dated).
     * Null if the assignment has never been revoked.
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
            effectiveStartDate = LocalDateTime.now(Clock.systemUTC());
        }
    }

    @PreUpdate
    protected void onUpdate() {
        lastModifiedAt = Instant.now(Clock.systemUTC());
    }

    /**
     * Check if this assignment is currently effective based on effective dates
     */
    public boolean isEffective() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        boolean afterStart = !now.isBefore(effectiveStartDate);
        boolean beforeEnd = effectiveEndDate == null || !now.isAfter(effectiveEndDate);
        return afterStart && beforeEnd;
    }

    /**
     * Check if this assignment covers a specific location
     */
    public boolean coversLocation(String locationId) {
        if (scopeType == ScopeType.GLOBAL) {
            return true;
        }
        return scopeLocationIds.contains(locationId);
    }

    /**
     * Custom setter for effectiveEndDate that automatically updates revokedAt.
     * When the effective end date is set (revocation), the revokedAt timestamp
     * is automatically set to the current time to track when the revocation occurred.
     *
     * @param effectiveEndDate the new effective end date
     */
    public void setEffectiveEndDate(LocalDateTime effectiveEndDate) {
        this.effectiveEndDate = effectiveEndDate;
        // Automatically update revokedAt when effectiveEndDate is set
        if (effectiveEndDate != null) {
            this.revokedAt = Instant.now(Clock.systemUTC());
        }
    }
}
