package com.positivity.securityservice.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents a user's assignment to a role with optional scope and effective dating.
 * Supports scoped RBAC where roles can be limited to specific locations.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "role_assignments")
public class RoleAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
     * Location IDs this role assignment applies to (only used when scopeType is LOCATION)
     */
    @ElementCollection
    @CollectionTable(name = "role_assignment_scope_locations", 
                     joinColumns = @JoinColumn(name = "role_assignment_id"))
    @Column(name = "location_id")
    private Set<String> scopeLocationIds = new HashSet<>();

    /**
     * Start date when this assignment becomes effective
     */
    @Column(nullable = false)
    private LocalDate effectiveStartDate;

    /**
     * End date when this assignment expires (null = no expiration)
     */
    private LocalDate effectiveEndDate;

    /**
     * When this assignment was created
     */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * User who created this assignment
     */
    @Column(nullable = false, length = 255)
    private String createdBy;

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
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (effectiveStartDate == null) {
            effectiveStartDate = LocalDate.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        lastModifiedAt = Instant.now();
    }

    /**
     * Check if this assignment is currently effective based on effective dates
     */
    public boolean isEffective() {
        LocalDate now = LocalDate.now();
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
}
