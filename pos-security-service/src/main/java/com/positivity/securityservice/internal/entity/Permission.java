package com.positivity.securityservice.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.time.TimeSource;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Represents a permission in the system following the domain:resource:action
 * or domain:action naming convention.
 * Examples: pricing:price_book:edit, inventory:adjustment:approve
 */
@Data
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "permissions", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class Permission {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /**
     * Permission name following format: domain:resource:action or domain:action
     * Must be lowercase, singular nouns, with action verbs
     */
    @Column(unique = true, nullable = false, length = 255)
    private String name;

    /**
     * Human-readable description of what this permission grants
     */
    @Column(length = 500)
    private String description;

    /**
     * Indicates whether this permission has been deprecated.
     */
    @Column(nullable = false)
    private boolean deprecated;

    /**
     * The domain this permission belongs to (e.g., pricing, inventory, security)
     */
    @Column(nullable = false, length = 50)
    private String domain;

    /**
     * The resource this permission applies to (e.g., price_book, adjustment, role)
     */
    @Column(nullable = false, length = 100)
    private String resource;

    /**
     * The action this permission allows (e.g., view, create, edit, approve)
     */
    @Column(nullable = false, length = 50)
    private String action;

    /**
     * When this permission was registered
     */
    @Column(nullable = false)
    private Instant registeredAt;

    /**
     * The service that registered this permission
     */
    @Column(nullable = false, length = 100)
    private String registeredByService;

    /**
     * Version of the permission schema (for future compatibility)
     */
    @Column(nullable = false)
    private String version = "1.0";

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Bit index from PermissionCode catalog for compact JWT encoding.
     * Null if this permission is not yet mapped to a PermissionCode entry.
     */
    @Column(name = "bit_index")
    private Integer bitIndex;

    @PrePersist
    protected void onCreate() {
        if (registeredAt == null) {
            registeredAt = TimeSource.instant();
        }
    }

    /**
     * Parse domain:resource:action format and set individual fields.
     * Also handles 2-segment domain:action format used by some domains.
     */
    public void parsePermissionName() {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Permission name cannot be null or empty");
        }

        String[] parts = name.split(":");
        if (parts.length == 3) {
            this.domain = parts[0].toLowerCase().trim();
            this.resource = parts[1].toLowerCase().trim();
            this.action = parts[2].toLowerCase().trim();
        } else if (parts.length == 2) {
            this.domain = parts[0].toLowerCase().trim();
            this.resource = "";
            this.action = parts[1].toLowerCase().trim();
        } else {
            throw new IllegalArgumentException(
                    "Permission name must follow format domain:resource:action or domain:action, got: " + name);
        }
    }
}
