package com.positivity.location.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Location aggregate representing physical sites, warehouses, or other
 * operational locations.
 *
 * Issue: CAP-214 #38
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Location {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void prePersist() {
        normalizeDerivedFields();
    }

    @PreUpdate
    public void preUpdate() {
        normalizeDerivedFields();
    }

    private void normalizeDerivedFields() {
        normalizedName = normalizeName(name);
        if (status == null || status.isBlank()) {
            status = active ? "ACTIVE" : "INACTIVE";
        }
    }

    private String normalizeName(String value) {
        if (value == null) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT).trim();
    }

    private String name;

    @Column(name = "normalized_name")
    private String normalizedName;
    /** Unique immutable business code for this location (e.g. "MAIN-WS-001"). */
    @Column(unique = true)
    private String code;

    private String status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "hr_location_id")
    private String hrLocationId;

    private String timezone;

    @Column(name = "operating_hours", columnDefinition = "TEXT")
    private String operatingHours;

    @Column(name = "holiday_closures", columnDefinition = "TEXT")
    private String holidayClosures;

    private Integer checkInBufferMinutes;
    private Integer cleanupBufferMinutes;
    // Issue CAP-214 #38: Persist default storage role assignments at site level.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_staging_location_id")
    private StorageLocationEntity defaultStagingLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_quarantine_location_id")
    private StorageLocationEntity defaultQuarantineLocation;

    public UUID getDefaultStagingLocationId() {
        return defaultStagingLocation != null ? defaultStagingLocation.getId() : null;
    }

    public UUID getDefaultQuarantineLocationId() {
        return defaultQuarantineLocation != null ? defaultQuarantineLocation.getId() : null;
    }

    @Version
    private Long version;

    /**
     * Reference to the GeographicalLocation entity in pos-location (ADR-0016).
     * Other services MUST NOT replicate address data; they store this ID and
     * query pos-location for full address details.
     */
    @Column(name = "geographical_location_id")
    private UUID geographicalLocationId;

    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private String country;
    private String mailingAddress;

    @Builder.Default
    @Column(name = "is_active", nullable = false, columnDefinition = "boolean default true")
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_type_id")
    private LocationType type;

    // Reference to Person (responsible) by ID from pos-people
    private Long responsiblePersonId;

    @Builder.Default
    // Bi-directional parent-child relationship
    @OneToMany(mappedBy = "child", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Set<LocationParent> parents = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Set<LocationParent> children = new HashSet<>();
}
